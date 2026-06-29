package dev.nilp0inter.subspace.audio

import dev.nilp0inter.subspace.model.SttStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SttController(
    private val scope: CoroutineScope,
    private val sco: ScoRoute,
    private val captureService: CaptureService,
    private val source: CaptureSource,
    private val output: PcmOutput,
    private val transcriptionService: PcmTranscriber,
) {
    private val _status = MutableStateFlow<SttStatus>(SttStatus.Idle)
    val status: StateFlow<SttStatus> = _status.asStateFlow()

    var enabled: Boolean = false
        private set

    private var pttDown: Boolean = false
    private var setupJob: Job? = null
    private var completionJob: Job? = null
    private var activeSession: CaptureSession? = null
    private var retainedAfterMaxDuration: RecordedPcm? = null
    private var transcribeJob: Job? = null

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value && activeSession == null && setupJob?.isActive != true) {
            _status.value = SttStatus.Idle
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
        val session = activeSession
        activeSession = null
        if (session != null) {
            scope.launch { captureService.cancelSession(session) }
        }
        retainedAfterMaxDuration = null
        sco.release()
        _status.value = SttStatus.Idle
    }

    private suspend fun startSession(route: ResolvedAudioRoute) {
        runCatching {
            _status.value = SttStatus.WaitingForAudio
            val result = captureService.startSession(
                source = route.source,
                sco = route.sco,
                output = route.output,
                shouldProceed = { pttDown },
            )
            when (result) {
                CaptureStartResult.SessionActive -> {
                    _status.value = SttStatus.Error("Capture session already active")
                }
                CaptureStartResult.ScoUnavailable -> {
                    _status.value = SttStatus.Error("SCO unavailable")
                }
                CaptureStartResult.Cancelled -> {
                    cancelSession(route)
                }
                CaptureStartResult.RecordingFailed -> {
                    _status.value = SttStatus.Error("Recording failed")
                }
                is CaptureStartResult.Started -> {
                    activeSession = result.session
                    _status.value = SttStatus.Recording
                    observeCompletion(result.session)
                }
            }
        }.onFailure { error ->
            _status.value = SttStatus.Error(error.message ?: "STT session failed")
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
                _status.value = SttStatus.MaxDurationReached
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

    private fun cancelSession(route: ResolvedAudioRoute) {
        completionJob?.cancel()
        completionJob = null
        val session = activeSession
        activeSession = null
        if (session != null) {
            scope.launch { captureService.cancelSession(session) }
        }
        retainedAfterMaxDuration = null
        scope.launch {
            route.output.releaseRoute()
            route.sco.release()
        }
        _status.value = SttStatus.Cancelled
    }

    private companion object {
        const val DEFAULT_RATE = 16_000
    }
}