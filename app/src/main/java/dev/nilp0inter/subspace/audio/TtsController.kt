package dev.nilp0inter.subspace.audio

import dev.nilp0inter.subspace.model.ScoState
import dev.nilp0inter.subspace.model.TtsModelStatus
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
 * TTS test controller.
 *
 * Synthesizes speech from text on demand and plays it back through the
 * connected Bluetooth SCO route. Does not record. PTT press while enabled
 * triggers synthesis (repurposed as a non-recording trigger); PTT release is a
 * no-op for this controller (playback runs to completion unless cancelled).
 *
 * On synthesis request while enabled: acquire SCO, synthesize off the main
 * thread with the current text/parameters, resample 44.1 kHz → SCO rate, play
 * through [PcmOutput], release SCO, and report status
 * at each stage. Handles empty text, model-not-ready, synthesis failure,
 * playback completion, and cancellation.
 */
class TtsController(
    private val scope: CoroutineScope,
    private val sco: ScoRoute,
    private val output: PcmOutput,
    private val synthesizer: TtsSynthesizer,
    private val synthesisDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _status = MutableStateFlow<TtsStatus>(TtsStatus.Idle)
    val status: StateFlow<TtsStatus> = _status.asStateFlow()

    var enabled: Boolean = false
        private set

    private var synthesisJob: Job? = null

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value && synthesisJob?.isActive != true) {
            _status.value = TtsStatus.Idle
        }
    }

    /**
     * Request synthesis with the given parameters. Called from the UI
     * "Synthesize" control or from PTT press.
     */
    fun synthesize(
        text: String,
        voiceStylePath: String,
        lang: String,
        totalSteps: Int,
        speed: Float,
        scoRate: Int,
    ) {
        if (!enabled) return
        if (text.isBlank()) {
            _status.value = TtsStatus.EmptyText
            return
        }
        synthesisJob?.cancel()
        synthesisJob = scope.launch {
            runSynthesis(text, voiceStylePath, lang, totalSteps, speed, scoRate)
        }
    }

    /** PTT press repurposed as a synthesis trigger (no recording). */
    fun onPttPressed(
        text: String,
        voiceStylePath: String,
        lang: String,
        totalSteps: Int,
        speed: Float,
        scoRate: Int,
    ) {
        synthesize(text, voiceStylePath, lang, totalSteps, speed, scoRate)
    }

    fun onPttPressed(
        route: ResolvedAudioRoute,
        text: String,
        voiceStylePath: String,
        lang: String,
        totalSteps: Int,
        speed: Float,
        scoRate: Int,
    ) {
        if (!enabled) return
        if (text.isBlank()) {
            _status.value = TtsStatus.EmptyText
            return
        }
        synthesisJob?.cancel()
        synthesisJob = scope.launch {
            runSynthesis(route, text, voiceStylePath, lang, totalSteps, speed, scoRate)
        }
    }

    /** PTT release is a no-op for the TTS controller. */
    fun onPttReleased() {
        // No-op: playback runs to completion unless cancelled.
    }

    /** Cancel any active or pending TTS work and release resources. */
    fun cancelAndRelease() {
        val activeJob = synthesisJob?.takeIf { it.isActive }
        activeJob?.cancel()
        _status.value = TtsStatus.Idle
    }

    suspend fun onInputReleased(
        text: String,
        voiceStylePath: String,
        lang: String,
        totalSteps: Int,
        speed: Float,
        scoRate: Int,
    ): ChannelInputResult {
        if (!enabled) return ChannelInputResult.None
        if (text.isBlank()) {
            _status.value = TtsStatus.EmptyText
            return ChannelInputResult.None
        }
        return synthesizePlayback(text, voiceStylePath, lang, totalSteps, speed, scoRate)
    }

    fun onInputPlaybackCompleted() {
        _status.value = TtsStatus.Idle
    }

    private suspend fun synthesizePlayback(
        text: String,
        voiceStylePath: String,
        lang: String,
        totalSteps: Int,
        speed: Float,
        scoRate: Int,
    ): ChannelInputResult {
        return try {
            _status.value = TtsStatus.Synthesizing
            val request = SynthesisRequest(
                text = text,
                voiceStylePath = voiceStylePath,
                lang = lang,
                totalSteps = totalSteps,
                speed = speed,
            )
            when (val outcome = withContext(synthesisDispatcher) { synthesizer.synthesize(request) }) {
                is SynthesisOutcome.Success -> {
                    if (outcome.samples.isEmpty()) {
                        _status.value = TtsStatus.Error("Synthesis produced no audio")
                        ChannelInputResult.None
                    } else {
                        _status.value = TtsStatus.Playing
                        val playback = TtsAudio.toScoPlayback(outcome.samples, scoRate)
                        if (playback.isEmpty) ChannelInputResult.None else ChannelInputResult.Playback(playback)
                    }
                }
                is SynthesisOutcome.ModelNotReady -> {
                    _status.value = TtsStatus.Error("TTS model not ready")
                    ChannelInputResult.None
                }
                is SynthesisOutcome.Failure -> {
                    _status.value = TtsStatus.Error(outcome.reason)
                    ChannelInputResult.None
                }
                SynthesisOutcome.EmptyText -> {
                    _status.value = TtsStatus.EmptyText
                    ChannelInputResult.None
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            _status.value = TtsStatus.Error(error.message ?: "TTS session failed")
            ChannelInputResult.None
        }
    }

    private suspend fun runSynthesis(
        text: String,
        voiceStylePath: String,
        lang: String,
        totalSteps: Int,
        speed: Float,
        scoRate: Int,
    ) {
        val route = ResolvedAudioRoute(sco, output, NoopCaptureSource)
        runSynthesis(route, text, voiceStylePath, lang, totalSteps, speed, scoRate)
    }

    private suspend fun runSynthesis(
        route: ResolvedAudioRoute,
        text: String,
        voiceStylePath: String,
        lang: String,
        totalSteps: Int,
        speed: Float,
        scoRate: Int,
    ) {
        var acquired = false
        try {
            _status.value = TtsStatus.WaitingForModel
            if (!route.sco.acquire()) {
                _status.value = TtsStatus.Error("SCO unavailable")
                return
            }
            acquired = true

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
                    route.output.play(playback)
                    _status.value = TtsStatus.Idle
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
        } finally {
            if (acquired) {
                route.output.releaseRoute()
            }
        }
    }
}
