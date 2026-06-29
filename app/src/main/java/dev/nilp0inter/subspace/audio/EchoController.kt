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
    private val captureService: CaptureService,
    private val source: CaptureSource,
    private val output: PcmOutput,
) {
    private val _status = MutableStateFlow<EchoStatus>(EchoStatus.Idle)
    val status: StateFlow<EchoStatus> = _status.asStateFlow()

    var enabled: Boolean = false
        private set

    private var pttDown: Boolean = false
    private var setupJob: Job? = null
    private var completionJob: Job? = null
    private var activeSession: CaptureSession? = null
    private var retainedAfterMaxDuration: RecordedPcm? = null

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value && activeSession == null && setupJob?.isActive != true) {
            _status.value = EchoStatus.Idle
        }
    }

    fun onPttPressed() {
        onPttPressed(ResolvedAudioRoute(sco, output, source))
    }

    fun onPttPressed(route: ResolvedAudioRoute) {
        pttDown = true
        if (!enabled) return
        if (setupJob?.isActive == true || activeSession != null) return

        retainedAfterMaxDuration = null
        setupJob = scope.launch { startEchoSession(route) }
    }

    fun onPttReleased() {
        onPttReleased(ResolvedAudioRoute(sco, output, source))
    }

    fun onPttReleased(route: ResolvedAudioRoute) {
        pttDown = false
        scope.launch { finishEchoSessionIfNeeded(route) }
    }

    fun cancelAndRelease(reason: String? = null) {
        setupJob?.cancel()
        completionJob?.cancel()
        completionJob = null
        val session = activeSession
        activeSession = null
        if (session != null) {
            scope.launch { captureService.cancelSession(session) }
        }
        retainedAfterMaxDuration = null
        sco.release()
        _status.value = if (reason == null) EchoStatus.Idle else EchoStatus.Error(reason)
    }

    private suspend fun startEchoSession(route: ResolvedAudioRoute) {
        runCatching {
            _status.value = EchoStatus.WaitingForAudio
            val result = captureService.startSession(
                source = route.source,
                sco = route.sco,
                output = route.output,
                shouldProceed = { pttDown },
            )
            when (result) {
                CaptureStartResult.SessionActive -> {
                    _status.value = EchoStatus.Error("Capture session already active")
                }
                CaptureStartResult.ScoUnavailable -> {
                    _status.value = EchoStatus.Error("SCO unavailable")
                }
                CaptureStartResult.Cancelled -> {
                    cancelSession(route)
                }
                CaptureStartResult.RecordingFailed -> {
                    _status.value = EchoStatus.Error("Recording failed")
                }
                is CaptureStartResult.Started -> {
                    activeSession = result.session
                    _status.value = EchoStatus.Recording
                    observeCompletion(result.session)
                }
            }
        }.onFailure { error ->
            _status.value = EchoStatus.Error(error.message ?: "Echo failed")
            val session = activeSession
            activeSession = null
            if (session != null) captureService.cancelSession(session)
        }
    }

    private fun observeCompletion(session: CaptureSession) {
        completionJob = scope.launch {
            val completion = session.completion.await()
            if (completion is CaptureCompletion.MaxDuration &&
                pttDown &&
                activeSession === session
            ) {
                retainedAfterMaxDuration = completion.recordedPcm
                _status.value = EchoStatus.MaxDurationReached
            }
        }
    }

    private suspend fun finishEchoSessionIfNeeded(route: ResolvedAudioRoute) {
        completionJob?.cancel()
        completionJob = null

        val session = activeSession
        val recording = if (session != null) {
            activeSession = null
            session.stop()
        } else {
            retainedAfterMaxDuration ?: RecordedPcm(shortArrayOf(), DEFAULT_RATE)
        }
        retainedAfterMaxDuration = null

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

    private fun cancelSession(route: ResolvedAudioRoute) {
        completionJob?.cancel()
        completionJob = null
        val session = activeSession
        activeSession = null
        if (session != null) {
            scope.launch { captureService.cancelSession(session) }
        }
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
        const val DEFAULT_RATE = 16_000
    }
}
