package dev.nilp0inter.subspace.audio

import dev.nilp0inter.subspace.model.EchoStatus
import dev.nilp0inter.subspace.model.EchoTimingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class EchoController(
    private val scope: CoroutineScope,
    private val sco: ScoRoute,
    private val recorder: AudioRecorder,
    private val output: PcmOutput,
) {
    private val _status = MutableStateFlow<EchoStatus>(EchoStatus.Idle)
    val status: StateFlow<EchoStatus> = _status.asStateFlow()

    var enabled: Boolean = false
        private set

    var timingMode: EchoTimingMode = EchoTimingMode.RecordAfterBeep
        private set

    private var pttDown: Boolean = false
    private var setupJob: Job? = null
    private var maxDurationJob: Job? = null
    private var closeScoJob: Job? = null
    private var retainedAfterMaxDuration: RecordedPcm? = null

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value && !recorder.isActive && setupJob?.isActive != true) {
            _status.value = EchoStatus.Idle
        }
    }

    fun setTimingMode(value: EchoTimingMode) {
        timingMode = value
    }

    fun onPttPressed() {
        pttDown = true
        if (!enabled) return
        if (setupJob?.isActive == true || recorder.isActive) return

        closeScoJob?.cancel()
        retainedAfterMaxDuration = null
        setupJob = scope.launch { startEchoSession() }
    }

    fun onPttReleased() {
        pttDown = false
        scope.launch { finishEchoSessionIfNeeded() }
    }

    fun cancelAndRelease(reason: String? = null) {
        setupJob?.cancel()
        maxDurationJob?.cancel()
        closeScoJob?.cancel()
        recorder.stopIfActiveOrEmpty()
        retainedAfterMaxDuration = null
        sco.release()
        _status.value = if (reason == null) EchoStatus.Idle else EchoStatus.Error(reason)
    }

    private suspend fun startEchoSession() {
        runCatching {
            _status.value = EchoStatus.WaitingForAudio
            if (!sco.acquire()) {
                _status.value = EchoStatus.Error("SCO unavailable")
                return
            }

            if (!pttDown) {
                cancelBeforeRecording()
                return
            }

            when (timingMode) {
                EchoTimingMode.RecordAfterBeep -> startAfterBeepMode()
                EchoTimingMode.RecordWhileBeepPlays -> startDuringBeepMode()
            }
        }.onFailure { error ->
            _status.value = EchoStatus.Error(error.message ?: "Echo failed")
            recorder.stopIfActiveOrEmpty()
        }
    }

    private suspend fun startAfterBeepMode() {
        _status.value = EchoStatus.Beeping
        output.playReadyBeep()
        if (!pttDown) {
            cancelBeforeRecording()
            return
        }

        if (!recorder.start()) {
            _status.value = EchoStatus.Error("Recording failed")
            return
        }
        _status.value = EchoStatus.Recording
        scheduleMaxDurationStop()
    }

    private suspend fun startDuringBeepMode() {
        if (!recorder.start()) {
            _status.value = EchoStatus.Error("Recording failed")
            return
        }
        _status.value = EchoStatus.Beeping
        output.playReadyBeep()
        if (pttDown) _status.value = EchoStatus.Recording
        scheduleMaxDurationStop()
    }

    private suspend fun finishEchoSessionIfNeeded() {
        val retained = retainedAfterMaxDuration
        retainedAfterMaxDuration = null
        maxDurationJob?.cancel()
        maxDurationJob = null

        val recording = retained ?: recorder.stopIfActiveOrEmpty()
        if (recording.isEmpty) {
            if (enabled || _status.value != EchoStatus.Idle) cancelBeforeRecording()
            return
        }

        runCatching {
            _status.value = EchoStatus.Playback
            output.play(recording)
            _status.value = EchoStatus.Warm
            closeScoJob = scope.launch {
                delay(30_000)
                sco.release()
                _status.value = EchoStatus.Idle
            }
        }.onFailure { error ->
            _status.value = EchoStatus.Error(error.message ?: "Playback failed")
        }
    }

    private fun scheduleMaxDurationStop() {
        maxDurationJob?.cancel()
        maxDurationJob = scope.launch {
            delay(60_000)
            if (pttDown && recorder.isActive) {
                retainedAfterMaxDuration = recorder.stopIfActiveOrEmpty()
                _status.value = EchoStatus.MaxDurationReached
            }
        }
    }

    private fun cancelBeforeRecording() {
        maxDurationJob?.cancel()
        maxDurationJob = null
        recorder.stopIfActiveOrEmpty()
        retainedAfterMaxDuration = null
        sco.release()
        _status.value = EchoStatus.Cancelled
    }
}
