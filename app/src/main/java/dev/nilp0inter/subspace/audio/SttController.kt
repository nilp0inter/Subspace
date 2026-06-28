package dev.nilp0inter.subspace.audio

import dev.nilp0inter.subspace.model.SttModelStatus
import dev.nilp0inter.subspace.model.SttStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * STT test controller.
 *
 * Mirrors [EchoController] lifecycle (SCO acquisition, ready beep, recording,
 * max-duration retention, cancellation) but transcribes the captured audio
 * with [TranscriptionService] instead of playing it back.
 *
 * Like [EchoController], this controller assumes a single PTT press at a time
 * and must be serialized by the owning service.
 */
class SttController(
    private val scope: CoroutineScope,
    private val sco: ScoRoute,
    private val recorder: AudioRecorder,
    private val output: PcmOutput,
    private val transcriptionService: TranscriptionService,
) {
    private val _status = MutableStateFlow<SttStatus>(SttStatus.Idle)
    val status: StateFlow<SttStatus> = _status.asStateFlow()

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
            _status.value = SttStatus.Idle
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

    fun onPttReleased() {
        onPttReleased(ResolvedAudioRoute(sco, output, recorder))
    }

    fun onPttReleased(route: ResolvedAudioRoute) {
        pttDown = false
        scope.launch { finishSessionIfNeeded(route) }
    }

    /** Cancel any active or pending STT work and release resources. */
    fun cancelAndRelease() {
        setupJob?.cancel()
        maxDurationJob?.cancel()
        transcribeJob?.cancel()
        recorder.stopIfActiveOrEmpty()
        retainedAfterMaxDuration = null
        sco.release()
        _status.value = SttStatus.Idle
    }

    private suspend fun startSession() {
        startSession(ResolvedAudioRoute(sco, output, recorder))
    }

    private suspend fun startSession(route: ResolvedAudioRoute) {
        runCatching {
            _status.value = SttStatus.WaitingForAudio
            if (!route.sco.acquire()) {
                _status.value = SttStatus.Error("SCO unavailable")
                return
            }

            if (!pttDown) {
                cancelSession(route)
                return
            }

            _status.value = SttStatus.Beeping
            route.output.playReadyBeep(route.sco.coldStart)
            if (!pttDown) {
                cancelSession(route)
                return
            }

            if (!route.recorder.start()) {
                _status.value = SttStatus.Error("Recording failed")
                return
            }
            _status.value = SttStatus.Recording
            scheduleMaxDurationStop()
        }.onFailure { error ->
            _status.value = SttStatus.Error(error.message ?: "STT session failed")
            recorder.stopIfActiveOrEmpty()
        }
    }

    private fun scheduleMaxDurationStop() {
        maxDurationJob?.cancel()
        maxDurationJob = scope.launch {
            delay(MAX_DURATION_MS)
            if (pttDown && recorder.isActive) {
                retainedAfterMaxDuration = recorder.stopIfActiveOrEmpty()
                _status.value = SttStatus.MaxDurationReached
            }
        }
    }

    private suspend fun finishSessionIfNeeded() {
        finishSessionIfNeeded(ResolvedAudioRoute(sco, output, recorder))
    }

    private suspend fun finishSessionIfNeeded(route: ResolvedAudioRoute) {
        maxDurationJob?.cancel()
        maxDurationJob = null

        val retained = retainedAfterMaxDuration
        retainedAfterMaxDuration = null
        val recording = retained ?: route.recorder.stopIfActiveOrEmpty()

        if (recording.isEmpty) {
            if (enabled || _status.value != SttStatus.Idle) cancelSession(route)
            _status.value = SttStatus.EmptyAudio
            return
        }

        _status.value = SttStatus.Transcribing
        transcribeJob = scope.launch {
            runCatching { transcriptionService.transcribe(recording.samples, recording.sampleRate) }
                .onSuccess { text ->
                    _status.value = SttStatus.Transcribed(text)
                    route.output.releaseRoute()
                    route.sco.release()
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _status.value = when (error) {
                        TranscriptionException.EmptyInput -> SttStatus.EmptyAudio
                        is TranscriptionException.Failed -> SttStatus.Error(error.reason)
                        TranscriptionException.ModelNotReady -> SttStatus.Error("STT model not ready")
                        else -> SttStatus.Error(error.message ?: "Transcription failed")
                    }
                    if (_status.value !is SttStatus.Transcribed) {
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
        _status.value = SttStatus.Cancelled
    }

    companion object {
        private const val MAX_DURATION_MS = 60_000L
    }
}
