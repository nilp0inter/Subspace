package dev.nilp0inter.subspace.audio

import dev.nilp0inter.subspace.model.SttModelStatus
import dev.nilp0inter.subspace.model.SttStatus
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
 * STT test controller.
 *
 * Mirrors [EchoController] lifecycle (SCO acquisition, ready beep, recording,
 * max-duration retention, cancellation) but transcribes the captured audio
 * with [SttTranscriber] instead of playing it back.
 *
 * Like [EchoController], this controller assumes a single PTT press at a time
 * and must be serialized by the owning service.
 */
class SttController(
    private val scope: CoroutineScope,
    private val sco: ScoRoute,
    private val recorder: AudioRecorder,
    private val output: PcmOutput,
    private val transcriber: SttTranscriber,
    private val transcriptionDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _status = MutableStateFlow<SttStatus>(SttStatus.Idle)
    val status: StateFlow<SttStatus> = _status.asStateFlow()

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
            _status.value = SttStatus.Idle
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

    fun onPttReleased() {
        pttDown = false
        scope.launch { finishSessionIfNeeded() }
    }

    /** Cancel any active or pending STT work and release resources. */
    fun cancelAndRelease() {
        setupJob?.cancel()
        maxDurationJob?.cancel()
        closeScoJob?.cancel()
        transcribeJob?.cancel()
        recorder.stopIfActiveOrEmpty()
        retainedAfterMaxDuration = null
        sco.release()
        _status.value = SttStatus.Idle
    }

    private suspend fun startSession() {
        runCatching {
            _status.value = SttStatus.WaitingForAudio
            if (!sco.acquire()) {
                _status.value = SttStatus.Error("SCO unavailable")
                return
            }

            if (!pttDown) {
                cancelBeforeRecording()
                return
            }

            _status.value = SttStatus.Beeping
            output.playReadyBeep()
            if (!pttDown) {
                cancelBeforeRecording()
                return
            }

            if (!recorder.start()) {
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
        maxDurationJob?.cancel()
        maxDurationJob = null

        val retained = retainedAfterMaxDuration
        retainedAfterMaxDuration = null
        val recording = retained ?: recorder.stopIfActiveOrEmpty()

        if (recording.isEmpty) {
            if (enabled || _status.value != SttStatus.Idle) cancelBeforeRecording()
            _status.value = SttStatus.EmptyAudio
            return
        }

        _status.value = SttStatus.Transcribing
        transcribeJob = scope.launch {
            val samples = SttAudio.toParakeetInput(recording)
            val outcome = withContext(transcriptionDispatcher) { transcriber.transcribe(samples) }
            when (outcome) {
                is TranscriptionOutcome.Success -> {
                    _status.value = SttStatus.Transcribed(outcome.text)
                    releaseScoAfterWarmup()
                }
                is TranscriptionOutcome.Failure -> {
                    _status.value = SttStatus.Error(outcome.reason)
                    sco.release()
                }
                TranscriptionOutcome.ModelNotReady -> {
                    _status.value = SttStatus.Error("STT model not ready")
                    sco.release()
                }
                TranscriptionOutcome.EmptyInput -> {
                    _status.value = SttStatus.EmptyAudio
                    sco.release()
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

    private fun cancelBeforeRecording() {
        maxDurationJob?.cancel()
        maxDurationJob = null
        recorder.stopIfActiveOrEmpty()
        retainedAfterMaxDuration = null
        sco.release()
        _status.value = SttStatus.Cancelled
    }

    companion object {
        private const val MAX_DURATION_MS = 60_000L
        private const val SCO_WARMUP_MS = 30_000L
    }
}