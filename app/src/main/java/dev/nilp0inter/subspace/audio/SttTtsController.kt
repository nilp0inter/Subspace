package dev.nilp0inter.subspace.audio

import dev.nilp0inter.subspace.model.SttTtsStatus
import dev.nilp0inter.subspace.model.TtsModelStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * STT↔TTS round-trip test controller.
 *
 * Composes the existing STT capture flow ([ScoRoute], [AudioRecorder], ready
 * beep, max-duration retention) with transcription ([SttTranscriber]) then
 * synthesis ([TtsSynthesizer]) then resample+playback.
 *
 * On PTT press while enabled: acquire SCO, ready beep, start recording (same
 * timing as STT). On PTT release: stop recording, normalize samples,
 * transcribe via [SttTranscriber] off the main thread; on success, synthesize
 * via [TtsSynthesizer] off the main thread using the transcript as text; on
 * success, resample and play through the headset; report status at each stage.
 *
 * Handles early release, empty audio, empty transcript, transcription failure,
 * synthesis failure, model-not-ready at either stage, playback completion, and
 * cancellation.
 */
class SttTtsController(
    private val scope: CoroutineScope,
    private val sco: ScoRoute,
    private val recorder: AudioRecorder,
    private val output: PcmOutput,
    private val transcriber: SttTranscriber,
    private val synthesizer: TtsSynthesizer,
    private val transcriptionDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val synthesisDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _status = MutableStateFlow<SttTtsStatus>(SttTtsStatus.Idle)
    val status: StateFlow<SttTtsStatus> = _status.asStateFlow()

    var enabled: Boolean = false
        private set

    private var pttDown: Boolean = false
    private var setupJob: Job? = null
    private var maxDurationJob: Job? = null
    private var closeScoJob: Job? = null
    private var retainedAfterMaxDuration: RecordedPcm? = null
    private var transcribeJob: Job? = null

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value && !recorder.isActive && setupJob?.isActive != true) {
            _status.value = SttTtsStatus.Idle
        }
    }

    fun onPttPressed() {
        pttDown = true
        if (!enabled) return
        if (setupJob?.isActive == true || recorder.isActive) return

        transcribeJob?.cancel()
        transcribeJob = null
        closeScoJob?.cancel()
        retainedAfterMaxDuration = null
        setupJob = scope.launch { startSession() }
    }

    fun onPttReleased(voiceStylePath: String, lang: String, totalSteps: Int, speed: Float, scoRate: Int) {
        pttDown = false
        scope.launch { finishSessionIfNeeded(voiceStylePath, lang, totalSteps, speed, scoRate) }
    }

    /** Cancel any active or pending STT↔TTS work and release resources. */
    fun cancelAndRelease() {
        setupJob?.cancel()
        maxDurationJob?.cancel()
        closeScoJob?.cancel()
        transcribeJob?.cancel()
        recorder.stopIfActiveOrEmpty()
        retainedAfterMaxDuration = null
        sco.release()
        _status.value = SttTtsStatus.Idle
    }

    private suspend fun startSession() {
        runCatching {
            _status.value = SttTtsStatus.WaitingForAudio
            if (!sco.acquire()) {
                _status.value = SttTtsStatus.Error("SCO unavailable")
                return
            }

            if (!pttDown) {
                cancelSession()
                return
            }

            _status.value = SttTtsStatus.Beeping
            output.playReadyBeep(sco.coldStart)
            if (!pttDown) {
                cancelSession()
                return
            }

            if (!recorder.start()) {
                _status.value = SttTtsStatus.Error("Recording failed")
                return
            }
            _status.value = SttTtsStatus.Recording
            scheduleMaxDurationStop()
        }.onFailure { error ->
            _status.value = SttTtsStatus.Error(error.message ?: "STT↔TTS session failed")
            recorder.stopIfActiveOrEmpty()
        }
    }

    private fun scheduleMaxDurationStop() {
        maxDurationJob?.cancel()
        maxDurationJob = scope.launch {
            delay(MAX_DURATION_MS)
            if (pttDown && recorder.isActive) {
                retainedAfterMaxDuration = recorder.stopIfActiveOrEmpty()
                _status.value = SttTtsStatus.MaxDurationReached
            }
        }
    }

    private suspend fun finishSessionIfNeeded(
        voiceStylePath: String,
        lang: String,
        totalSteps: Int,
        speed: Float,
        scoRate: Int,
    ) {
        maxDurationJob?.cancel()
        maxDurationJob = null

        val retained = retainedAfterMaxDuration
        retainedAfterMaxDuration = null
        val recording = retained ?: recorder.stopIfActiveOrEmpty()

        if (recording.isEmpty) {
            if (enabled || _status.value != SttTtsStatus.Idle) cancelSession()
            _status.value = SttTtsStatus.EmptyAudio
            return
        }

        _status.value = SttTtsStatus.Transcribing
        transcribeJob = scope.launch {
            val samples = SttAudio.toParakeetInput(recording)
            val transcriptOutcome = withContext(transcriptionDispatcher) { transcriber.transcribe(samples) }
            when (transcriptOutcome) {
                is TranscriptionOutcome.Success -> {
                    val transcript = transcriptOutcome.text
                    if (transcript.isBlank()) {
                        _status.value = SttTtsStatus.EmptyTranscript
                        releaseScoAfterWarmup()
                        return@launch
                    }
                    _status.value = SttTtsStatus.Transcript(transcript)
                    _status.value = SttTtsStatus.Synthesizing
                    val synthRequest = SynthesisRequest(
                        text = transcript,
                        voiceStylePath = voiceStylePath,
                        lang = lang,
                        totalSteps = totalSteps,
                        speed = speed,
                    )
                    val synthOutcome = withContext(synthesisDispatcher) { synthesizer.synthesize(synthRequest) }
                    when (synthOutcome) {
                        is SynthesisOutcome.Success -> {
                            if (synthOutcome.samples.isEmpty()) {
                                _status.value = SttTtsStatus.Error("Synthesis produced no audio")
                                releaseScoAfterWarmup()
                            } else {
                                _status.value = SttTtsStatus.Playing
                                val playback = TtsAudio.toScoPlayback(synthOutcome.samples, scoRate)
                                if (!playback.isEmpty) output.play(playback)
                                _status.value = SttTtsStatus.Idle
                                releaseScoAfterWarmup()
                            }
                        }
                        is SynthesisOutcome.ModelNotReady -> {
                            _status.value = SttTtsStatus.Error("TTS model not ready")
                            releaseScoAfterWarmup()
                        }
                        is SynthesisOutcome.Failure -> {
                            _status.value = SttTtsStatus.Error(synthOutcome.reason)
                            releaseScoAfterWarmup()
                        }
                        SynthesisOutcome.EmptyText -> {
                            _status.value = SttTtsStatus.EmptyTranscript
                            releaseScoAfterWarmup()
                        }
                    }
                }
                is TranscriptionOutcome.Failure -> {
                    _status.value = SttTtsStatus.Error("Transcription failed: ${transcriptOutcome.reason}")
                    releaseScoAfterWarmup()
                }
                TranscriptionOutcome.ModelNotReady -> {
                    _status.value = SttTtsStatus.Error("STT model not ready")
                    releaseScoAfterWarmup()
                }
                TranscriptionOutcome.EmptyInput -> {
                    _status.value = SttTtsStatus.EmptyAudio
                    releaseScoAfterWarmup()
                }
            }
        }
    }

    private fun releaseScoAfterWarmup() {
        closeScoJob = scope.launch {
            delay(SCO_WARMUP_MS)
            sco.release()
        }
    }

    private fun cancelSession() {
        maxDurationJob?.cancel()
        maxDurationJob = null
        recorder.stopIfActiveOrEmpty()
        retainedAfterMaxDuration = null
        releaseScoAfterWarmup()
        _status.value = SttTtsStatus.Cancelled
    }

    companion object {
        private const val MAX_DURATION_MS = 60_000L
        private const val SCO_WARMUP_MS = 30_000L
    }
}