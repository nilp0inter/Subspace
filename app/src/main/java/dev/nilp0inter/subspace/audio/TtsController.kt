package dev.nilp0inter.subspace.audio

import dev.nilp0inter.subspace.model.ScoState
import dev.nilp0inter.subspace.model.TtsStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Host diagnostic TTS controller.
 *
 * Synthesizes speech from an explicit UI request and plays it through the
 * connected Bluetooth SCO route. It does not participate in channel PTT or
 * audio-input lifecycle ownership; channel runtimes use semantic host
 * capabilities instead.
 */
class TtsController(
    private val scope: CoroutineScope,
    private val synthesizer: TtsSynthesizer,
    private val play: suspend (RecordedPcm) -> Boolean,
    private val synthesisDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _status = MutableStateFlow<TtsStatus>(TtsStatus.Idle)
    val status: StateFlow<TtsStatus> = _status.asStateFlow()

    private var synthesisJob: Job? = null


    /** Request synthesis from the host diagnostic UI. */
    fun synthesize(
        text: String,
        voiceStylePath: String,
        lang: String,
        totalSteps: Int,
        speed: Float,
        scoRate: Int,
    ) {
        if (text.isBlank()) {
            _status.value = TtsStatus.EmptyText
            return
        }
        synthesisJob?.cancel()
        synthesisJob = scope.launch {
            runSynthesis(text, voiceStylePath, lang, totalSteps, speed, scoRate)
        }
    }


    /** Cancel any active or pending TTS work and release resources. */
    fun cancelAndRelease() {
        val activeJob = synthesisJob?.takeIf { it.isActive }
        activeJob?.cancel()
        _status.value = TtsStatus.Idle
    }


    private suspend fun runSynthesis(
        text: String,
        voiceStylePath: String,
        lang: String,
        totalSteps: Int,
        speed: Float,
        scoRate: Int,
    ) {
        try {
            _status.value = TtsStatus.WaitingForModel
            _status.value = TtsStatus.Synthesizing
            val request = SynthesisRequest(
                text = text,
                voiceStylePath = voiceStylePath,
                lang = lang,
                totalSteps = totalSteps,
                speed = speed,
            )
            val outcome = withContext(synthesisDispatcher) { synthesizer.synthesize(request) }

            when (outcome) {
                is SynthesisOutcome.Success -> {
                    if (outcome.samples.isEmpty()) {
                        _status.value = TtsStatus.Error("Synthesis produced no audio")
                        return
                    }
                    _status.value = TtsStatus.Playing
                    val playback = TtsAudio.toScoPlayback(outcome.samples, scoRate)
                    if (playback.isEmpty) {
                        _status.value = TtsStatus.Idle
                        return
                    }
                    if (play(playback)) {
                        _status.value = TtsStatus.Idle
                    } else {
                        _status.value = TtsStatus.Error("Audio unavailable")
                    }
                }
                is SynthesisOutcome.ModelNotReady -> {
                    _status.value = TtsStatus.Error("TTS model not ready")
                }
                is SynthesisOutcome.Failure -> {
                    _status.value = TtsStatus.Error(outcome.reason)
                }
                SynthesisOutcome.EmptyText -> {
                    _status.value = TtsStatus.EmptyText
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            _status.value = TtsStatus.Error(error.message ?: "TTS session failed")
        }
    }
}
