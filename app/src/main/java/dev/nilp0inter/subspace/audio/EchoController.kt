package dev.nilp0inter.subspace.audio

import dev.nilp0inter.subspace.model.EchoStatus
import kotlinx.coroutines.CancellationException
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
        scope.launch { output.releaseRoute() }
        _status.value = if (reason == null) EchoStatus.Idle else EchoStatus.Error(reason)
    }

    fun onInputStarted(session: ChannelAudioInputSession) {
        pttDown = true
        retainedAfterMaxDuration = null
        if (enabled) _status.value = EchoStatus.Recording
    }

    suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
        pttDown = false
        if (recording.isEmpty) {
            _status.value = EchoStatus.Cancelled
            return ChannelInputResult.None
        }
        _status.value = EchoStatus.Playback
        return ChannelInputResult.Playback(recording)
    }

    fun onInputPlaybackCompleted() {
        _status.value = EchoStatus.Warm
        scope.launch {
            delay(COOLDOWN_MS)
            if (_status.value == EchoStatus.Warm) {
                _status.value = EchoStatus.Idle
            }
        }
    }

    fun onInputCancelled(reason: String? = null) {
        retainedAfterMaxDuration = null
        _status.value = if (reason == null) EchoStatus.Cancelled else EchoStatus.Error(reason)
        scope.launch {
            delay(COOLDOWN_MS)
            if (_status.value == EchoStatus.Cancelled) {
                _status.value = EchoStatus.Idle
            }
        }
    }

    fun onInputFailed(reason: String) {
        _status.value = EchoStatus.Error(reason)
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
                    // Service already released SCO on this branch; just transition status.
                    _status.value = EchoStatus.Cancelled
                    scope.launch {
                        delay(COOLDOWN_MS)
                        if (_status.value == EchoStatus.Cancelled) {
                            _status.value = EchoStatus.Idle
                        }
                    }
                }
                CaptureStartResult.RecordingFailed -> {
                    // Service already released SCO on this branch.
                    _status.value = EchoStatus.Error("Recording failed")
                }
                is CaptureStartResult.RecordingSilenced -> {
                    _status.value = EchoStatus.Error("Recording silenced")
                }
                is CaptureStartResult.Started -> {
                    activeSession = result.session
                    _status.value = EchoStatus.Recording
                    observeCompletion(result.session)
                }
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
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
            // No active session and no retained PCM means the setup job is
            // still in flight (SCO acquire / beep) or already failed. The
            // service owns SCO release on the Cancelled / RecordingFailed
            // branches, so we must NOT release the route here — doing so
            // would double-release against the service's own release.
            // Transition to Cancelled first, then cancel the in-flight setup
            // so the service observes `shouldProceed() == false` and releases
            // SCO itself.
            if (session == null) {
                if (_status.value != EchoStatus.Idle && _status.value !is EchoStatus.Error) {
                    _status.value = EchoStatus.Cancelled
                    scope.launch {
                        delay(COOLDOWN_MS)
                        if (_status.value == EchoStatus.Cancelled) {
                            _status.value = EchoStatus.Idle
                        }
                    }
                }
                setupJob?.cancel()
                setupJob = null
            } else if (enabled || _status.value != EchoStatus.Idle) {
                cancelSession(route)
            }
            return
        }

        runCatching {
            _status.value = EchoStatus.Playback
            route.output.play(recording)
            _status.value = EchoStatus.Warm
            delay(COOLDOWN_MS)
            route.output.releaseRoute()
            if (_status.value == EchoStatus.Warm) {
                _status.value = EchoStatus.Idle
            }
        }.onFailure { error ->
            _status.value = EchoStatus.Error(error.message ?: "Playback failed")
            route.output.releaseRoute()
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
        scope.launch { route.output.releaseRoute() }
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
