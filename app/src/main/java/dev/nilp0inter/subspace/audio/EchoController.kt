package dev.nilp0inter.subspace.audio

import dev.nilp0inter.subspace.model.EchoStatus
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

    private var pttDown: Boolean = false
    private var setupJob: Job? = null
    private var maxDurationJob: Job? = null
    private var retainedAfterMaxDuration: RecordedPcm? = null

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value && !recorder.isActive && setupJob?.isActive != true) {
            _status.value = EchoStatus.Idle
        }
    }

    fun onPttPressed() {
        onPttPressed(ResolvedAudioRoute(sco, output, recorder))
    }

    fun onPttPressed(route: ResolvedAudioRoute) {
        pttDown = true
        if (!enabled) return
        if (setupJob?.isActive == true || recorder.isActive) return

        retainedAfterMaxDuration = null
        setupJob = scope.launch { startEchoSession(route) }
    }

    fun onPttReleased() {
        onPttReleased(ResolvedAudioRoute(sco, output, recorder))
    }

    fun onPttReleased(route: ResolvedAudioRoute) {
        pttDown = false
        scope.launch { finishEchoSessionIfNeeded(route) }
    }

    fun cancelAndRelease(reason: String? = null) {
        setupJob?.cancel()
        maxDurationJob?.cancel()
        recorder.stopIfActiveOrEmpty()
        retainedAfterMaxDuration = null
        sco.release()
        _status.value = if (reason == null) EchoStatus.Idle else EchoStatus.Error(reason)
    }

    private suspend fun startEchoSession() {
        startEchoSession(ResolvedAudioRoute(sco, output, recorder))
    }

    private suspend fun startEchoSession(route: ResolvedAudioRoute) {
        runCatching {
            _status.value = EchoStatus.WaitingForAudio
            if (!route.sco.acquire()) {
                _status.value = EchoStatus.Error("SCO unavailable")
                return
            }

            if (!pttDown) {
                cancelSession(route)
                return
            }

            startRecording(route)
        }.onFailure { error ->
            _status.value = EchoStatus.Error(error.message ?: "Echo failed")
            recorder.stopIfActiveOrEmpty()
        }
    }

    private suspend fun startRecording(route: ResolvedAudioRoute) {
        _status.value = EchoStatus.Beeping
        route.output.playReadyBeep(route.sco.coldStart)
        if (!pttDown) {
            cancelSession(route)
            return
        }

        if (!route.recorder.start()) {
            _status.value = EchoStatus.Error("Recording failed")
            return
        }
        _status.value = EchoStatus.Recording
        scheduleMaxDurationStop()
    }

    private suspend fun finishEchoSessionIfNeeded() {
        finishEchoSessionIfNeeded(ResolvedAudioRoute(sco, output, recorder))
    }

    private suspend fun finishEchoSessionIfNeeded(route: ResolvedAudioRoute) {
        val retained = retainedAfterMaxDuration
        retainedAfterMaxDuration = null
        maxDurationJob?.cancel()
        maxDurationJob = null

        val recording = retained ?: route.recorder.stopIfActiveOrEmpty()
        if (recording.isEmpty) {
            if (enabled || _status.value != EchoStatus.Idle) cancelSession(route)
            return
        }

        runCatching {
            _status.value = EchoStatus.Playback
            route.output.play(recording)
            _status.value = EchoStatus.Warm
            delay(COOLDOWN_MS)
            route.sco.release()
            if (_status.value == EchoStatus.Warm) {
                _status.value = EchoStatus.Idle
            }
        }.onFailure { error ->
            _status.value = EchoStatus.Error(error.message ?: "Playback failed")
            route.sco.release()
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

    private fun cancelSession() {
        cancelSession(ResolvedAudioRoute(sco, output, recorder))
    }

    private fun cancelSession(route: ResolvedAudioRoute) {
        maxDurationJob?.cancel()
        maxDurationJob = null
        route.recorder.stopIfActiveOrEmpty()
        retainedAfterMaxDuration = null
        route.sco.release()
        _status.value = EchoStatus.Cancelled
        scope.launch {
            delay(COOLDOWN_MS)
            if (_status.value == EchoStatus.Cancelled) {
                _status.value = EchoStatus.Idle
            }
        }
    }

    private companion object {
        const val COOLDOWN_MS = 30_000L
    }
}
