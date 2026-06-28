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
    private var retainedAfterMaxDuration: RecordedPcm? = null
    private var transcribeJob: Job? = null

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value && !recorder.isActive && setupJob?.isActive != true) {
            _status.value = SttTtsStatus.Idle
        }
    }

    fun onPttPressed() {
        onPttPressed(ResolvedAudioRoute(sco, output, recorder))
    }

    fun onPttPressed(route: ResolvedAudioRoute) {
        pttDown = true
        if (!enabled) return
        if (setupJob?.isActive == true || recorder.isActive) return

        transcribeJob?.cancel()
        transcribeJob = null
        retainedAfterMaxDuration = null
        setupJob = scope.launch { startSession(route) }
    }

    fun onPttReleased(voiceStylePath: String, lang: String, totalSteps: Int, speed: Float, scoRate: Int) {
        onPttReleased(ResolvedAudioRoute(sco, output, recorder), voiceStylePath, lang, totalSteps, speed, scoRate)
    }

    fun onPttReleased(route: ResolvedAudioRoute, voiceStylePath: String, lang: String, totalSteps: Int, speed: Float, scoRate: Int) {
        pttDown = false
        scope.launch { finishSessionIfNeeded(route, voiceStylePath, lang, totalSteps, speed, scoRate) }
    }

    /** Cancel any active or pending STT↔TTS work and release resources. */
    fun cancelAndRelease() {
        setupJob?.cancel()
        maxDurationJob?.cancel()
        transcribeJob?.cancel()
        recorder.stopIfActiveOrEmpty()
        retainedAfterMaxDuration = null
        sco.release()
        _status.value = SttTtsStatus.Idle
    }

    private suspend fun startSession() {
        startSession(ResolvedAudioRoute(sco, output, recorder))
    }

    private suspend fun startSession(route: ResolvedAudioRoute) {
        runCatching {
            _status.value = SttTtsStatus.WaitingForAudio
            if (!route.sco.acquire()) {
                _status.value = SttTtsStatus.Error("SCO unavailable")
                return
            }

            if (!pttDown) {
                cancelSession(route)
                return
            }

            _status.value = SttTtsStatus.Beeping
            route.output.playReadyBeep(route.sco.coldStart)
            if (!pttDown) {
                cancelSession(route)
                return
            }

            if (!route.recorder.start()) {
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
        finishSessionIfNeeded(ResolvedAudioRoute(sco, output, recorder), voiceStylePath, lang, totalSteps, speed, scoRate)
    }

    private suspend fun finishSessionIfNeeded(
        route: ResolvedAudioRoute,
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
        val recording = retained ?: route.recorder.stopIfActiveOrEmpty()

        if (recording.isEmpty) {
            if (enabled || _status.value != SttTtsStatus.Idle) cancelSession(route)
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
                        route.output.releaseRoute()
                        route.sco.release()
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
                                route.output.releaseRoute()
                                route.sco.release()
                            } else {
                                _status.value = SttTtsStatus.Playing
                                val playback = TtsAudio.toScoPlayback(synthOutcome.samples, scoRate)
                                if (!playback.isEmpty) route.output.play(playback)
                                _status.value = SttTtsStatus.Idle
                                route.sco.release()
                            }
                        }
                        is SynthesisOutcome.ModelNotReady -> {
                            _status.value = SttTtsStatus.Error("TTS model not ready")
                            route.output.releaseRoute()
                            route.sco.release()
                        }
                        is SynthesisOutcome.Failure -> {
                            _status.value = SttTtsStatus.Error(synthOutcome.reason)
                            route.output.releaseRoute()
                            route.sco.release()
                        }
                        SynthesisOutcome.EmptyText -> {
                            _status.value = SttTtsStatus.EmptyTranscript
                            route.output.releaseRoute()
                            route.sco.release()
                        }
                    }
                }
                is TranscriptionOutcome.Failure -> {
                    _status.value = SttTtsStatus.Error("Transcription failed: ${transcriptOutcome.reason}")
                    route.output.releaseRoute()
                    route.sco.release()
                }
                TranscriptionOutcome.ModelNotReady -> {
                    _status.value = SttTtsStatus.Error("STT model not ready")
                    route.output.releaseRoute()
                    route.sco.release()
                }
                TranscriptionOutcome.EmptyInput -> {
                    _status.value = SttTtsStatus.EmptyAudio
                    route.output.releaseRoute()
                    route.sco.release()
                }
            }
        }
    }

    private fun cancelSession() {
        cancelSession(ResolvedAudioRoute(sco, output, recorder))
    }

    private fun cancelSession(route: ResolvedAudioRoute) {
        maxDurationJob?.cancel()
        maxDurationJob = null
        route.recorder.stopIfActiveOrEmpty()
        retainedAfterMaxDuration = null
        scope.launch {
            route.output.releaseRoute()
            route.sco.release()
        }
        _status.value = SttTtsStatus.Cancelled
    }

    companion object {
        private const val MAX_DURATION_MS = 60_000L
    }
}
