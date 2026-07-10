package dev.nilp0inter.subspace.channel

import android.util.Log
import dev.nilp0inter.subspace.audio.*
import dev.nilp0inter.subspace.bluetooth.SleepwalkerBleConnection
import dev.nilp0inter.subspace.model.KeyboardConnectionState
import dev.nilp0inter.subspace.model.KeyboardStatus
import io.sleepwalker.core.hid.LowLevelHid
import io.sleepwalker.core.hid.LowLevelOp
import io.sleepwalker.core.hid.toFrameBytes
import io.sleepwalker.core.keymap.HostProfile
import io.sleepwalker.core.keymap.KeymapDatabase
import io.sleepwalker.core.text.TapScriptCompiler
import io.sleepwalker.core.text.TextPlanner
import io.sleepwalker.core.protocol.Usages
import io.sleepwalker.core.text.TextRenderingFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class KeyboardPttController(
    private val scope: CoroutineScope,
    private val sco: ScoRoute,
    private val captureService: CaptureService,
    private val source: CaptureSource,
    private val output: PcmOutput,
    private val transcriptionService: PcmTranscriber,
    private val connection: SleepwalkerBleConnection,
    private val hid: LowLevelHid,
    private val keymapDatabase: KeymapDatabase,
    private val hostProfileProvider: () -> HostProfile,
) {
    companion object {
        private const val TAG = "SubspaceRoute"
        private const val DEFAULT_RATE = 16_000
    }

    private val _status = MutableStateFlow<KeyboardStatus>(KeyboardStatus.Idle)
    val status: StateFlow<KeyboardStatus> = _status.asStateFlow()

    var enabled: Boolean = false
        private set

    private var pttDown: Boolean = false
    private var setupJob: Job? = null
    private var completionJob: Job? = null
    private var activeSession: CaptureSession? = null
    private var retainedAfterMaxDuration: RecordedPcm? = null
    private var transcribeJob: Job? = null
    private var typingJob: Job? = null
    private var armed: Boolean = false

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value && activeSession == null && setupJob?.isActive != true && transcribeJob?.isActive != true && typingJob?.isActive != true) {
            _status.value = KeyboardStatus.Idle
        }
    }

    fun onPttPressed() {
        onPttPressed(ResolvedAudioRoute(sco, output, source))
    }

    fun onPttPressed(route: ResolvedAudioRoute) {
        pttDown = true
        if (!enabled) return
        if (setupJob?.isActive == true || activeSession != null) return

        transcribeJob?.cancel()
        transcribeJob = null
        typingJob?.cancel()
        typingJob = null
        retainedAfterMaxDuration = null
        setupJob = scope.launch { startSession(route) }
    }

    fun onPttReleased() {
        onPttReleased(ResolvedAudioRoute(sco, output, source))
    }

    fun onPttReleased(route: ResolvedAudioRoute) {
        pttDown = false
        scope.launch { finishSessionIfNeeded(route) }
    }

    fun cancelAndRelease() {
        setupJob?.cancel()
        completionJob?.cancel()
        completionJob = null
        transcribeJob?.cancel()
        typingJob?.cancel()
        val session = activeSession
        activeSession = null
        if (session != null) {
            scope.launch { captureService.cancelSession(session) }
        }
        retainedAfterMaxDuration = null

        if (armed && connection.connectionState.value == KeyboardConnectionState.Connected) {
            scope.launch {
                try {
                    connection.sendOp(hid.kill())
                } catch (e: Exception) {
                    Log.d(TAG, "BLE_KILL_FAILED msg=${e.message}")
                }
            }
        }
        armed = false

        scope.launch { output.releaseRoute() }
        _status.value = KeyboardStatus.Idle
    }

    fun onInputStarted(session: ChannelAudioInputSession) {
        pttDown = true
        transcribeJob?.cancel()
        transcribeJob = null
        typingJob?.cancel()
        typingJob = null
        retainedAfterMaxDuration = null
        if (enabled) _status.value = KeyboardStatus.Recording
    }

    suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
        pttDown = false
        retainedAfterMaxDuration = null
        if (recording.isEmpty) {
            _status.value = KeyboardStatus.EmptyAudio
            return ChannelInputResult.None
        }
        _status.value = KeyboardStatus.Transcribing
        try {
            val text = transcriptionService.transcribe(recording.samples, recording.sampleRate)
            startTyping(text)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _status.value = when (error) {
                TranscriptionException.EmptyInput -> KeyboardStatus.EmptyAudio
                is TranscriptionException.Failed -> KeyboardStatus.Error(error.reason)
                TranscriptionException.ModelNotReady -> KeyboardStatus.Error("STT model not ready")
                else -> KeyboardStatus.Error(error.message ?: "Transcription failed")
            }
        }
        return ChannelInputResult.None
    }

    fun onInputCancelled(reason: String? = null) {
        transcribeJob?.cancel()
        typingJob?.cancel()
        retainedAfterMaxDuration = null
        _status.value = if (reason == null) KeyboardStatus.Cancelled else KeyboardStatus.Error(reason)
    }

    fun onInputFailed(reason: String) {
        _status.value = KeyboardStatus.Error(reason)
    }

    fun sendEnter() {
        scope.launch {
            try {
                connection.sendOp(hid.arm())
                connection.sendOp(hid.keyTap(Usages.USB_KEY_ENTER))
                connection.sendOp(hid.disarm())
            } catch (e: Exception) {
                Log.d(TAG, "SEND_ENTER_FAILED msg=${e.message}")
            }
        }
    }

    private suspend fun startSession(route: ResolvedAudioRoute) {
        runCatching {
            _status.value = KeyboardStatus.WaitingForAudio
            val result = captureService.startSession(
                source = route.source,
                sco = route.sco,
                output = route.output,
                shouldProceed = { pttDown },
            )
            when (result) {
                CaptureStartResult.SessionActive -> {
                    _status.value = KeyboardStatus.Error("Capture session already active")
                }
                CaptureStartResult.ScoUnavailable -> {
                    _status.value = KeyboardStatus.Error("SCO unavailable")
                }
                CaptureStartResult.Cancelled -> {
                    _status.value = KeyboardStatus.Cancelled
                }
                CaptureStartResult.RecordingFailed -> {
                    _status.value = KeyboardStatus.Error("Recording failed")
                }
                is CaptureStartResult.RecordingSilenced -> {
                    _status.value = KeyboardStatus.Error("Recording silenced")
                }
                is CaptureStartResult.Started -> {
                    activeSession = result.session
                    _status.value = KeyboardStatus.Recording
                    observeCompletion(result.session)
                }
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            _status.value = KeyboardStatus.Error(error.message ?: "Keyboard session failed")
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
                _status.value = KeyboardStatus.MaxDurationReached
            }
        }
    }

    private suspend fun finishSessionIfNeeded(route: ResolvedAudioRoute) {
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
            if (session == null) {
                if (_status.value != KeyboardStatus.Idle && _status.value !is KeyboardStatus.Error) {
                    _status.value = KeyboardStatus.Cancelled
                }
                setupJob?.cancel()
                setupJob = null
            } else {
                if (enabled || _status.value != KeyboardStatus.Idle) cancelSession(route)
                _status.value = KeyboardStatus.EmptyAudio
            }
            return
        }

        _status.value = KeyboardStatus.Transcribing
        transcribeJob = scope.launch {
            var success = false
            try {
                val text = transcriptionService.transcribe(recording.samples, recording.sampleRate)
                success = true
                startTyping(text)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _status.value = when (error) {
                    TranscriptionException.EmptyInput -> KeyboardStatus.EmptyAudio
                    is TranscriptionException.Failed -> KeyboardStatus.Error(error.reason)
                    TranscriptionException.ModelNotReady -> KeyboardStatus.Error("STT model not ready")
                    else -> KeyboardStatus.Error(error.message ?: "Transcription failed")
                }
            } finally {
                if (!success) {
                    route.output.releaseRoute()
                }
            }
        }
    }

    private fun startTyping(text: String) {
        if (text.isEmpty()) return
        if (text.endsWith(" ")) return
        val adjustedText = "$text "
        typingJob = scope.launch {
            try {
                _status.value = KeyboardStatus.Typing
                val hostProfile = hostProfileProvider()
                val result = TextPlanner(database = keymapDatabase, hid = hid).plan(adjustedText, hostProfile)
                val plan = result.plan
                val failure = result.failure
                if (plan == null || failure != null) {
                    val reason = when (failure) {
                        is TextRenderingFailure.MissingLayout -> "Missing layout for ${failure.profile.key}"
                        is TextRenderingFailure.UnrepresentableGlyph -> "Unrepresentable character '${failure.ch}' for profile ${failure.profile.key}"
                        else -> "Text rendering failed"
                    }
                    _status.value = KeyboardStatus.Error(reason)
                    return@launch
                }

                val compiledOps = TapScriptCompiler.compile(plan, hid)

                armed = true
                connection.sendOp(hid.arm())

                for (op in compiledOps) {
                    connection.sendOp(op)
                }

                val lastOp = compiledOps.lastOrNull()
                if (lastOp != null) {
                    connection.awaitAck(lastOp.seqId)
                }

                connection.sendOp(hid.disarm())
                armed = false
                _status.value = KeyboardStatus.Done(text)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (armed && connection.connectionState.value == KeyboardConnectionState.Connected) {
                    try {
                        connection.sendOp(hid.kill())
                    } catch (killEx: Exception) {
                        Log.d(TAG, "BLE_KILL_FAILED msg=${killEx.message}")
                    }
                }
                armed = false
                _status.value = KeyboardStatus.Error(e.message ?: "Typing failed")
            } finally {
                output.releaseRoute()
            }
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
        _status.value = KeyboardStatus.Cancelled
    }
}
