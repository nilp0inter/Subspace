package dev.nilp0inter.subspace.webhook

import dev.nilp0inter.subspace.audio.CaptureService
import dev.nilp0inter.subspace.audio.CaptureSession
import dev.nilp0inter.subspace.audio.CaptureStartResult
import dev.nilp0inter.subspace.audio.PcmTranscriber
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.audio.ResolvedAudioRoute
import dev.nilp0inter.subspace.model.WebhookChannel
import dev.nilp0inter.subspace.model.WebhookStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WebhookPttController(
    private val scope: CoroutineScope,
    private val captureService: CaptureService,
    private val transcriber: PcmTranscriber,
    private val client: WebhookClient,
    private val channelProvider: () -> WebhookChannel,
) {
    private val _status = MutableStateFlow<WebhookStatus>(WebhookStatus.Idle)
    val status: StateFlow<WebhookStatus> = _status.asStateFlow()

    private var pttDown: Boolean = false
    private var setupJob: Job? = null
    private var activeSession: CaptureSession? = null
    private var sendJob: Job? = null

    fun onPttPressed(route: ResolvedAudioRoute) {
        pttDown = true
        if (setupJob?.isActive == true || activeSession != null) return

        sendJob?.cancel()
        sendJob = null
        setupJob = scope.launch { startSession(route) }
    }

    fun onPttReleased(route: ResolvedAudioRoute) {
        pttDown = false
        scope.launch { finishSessionIfNeeded(route) }
    }

    fun cancelAndRelease(route: ResolvedAudioRoute? = null) {
        setupJob?.cancel()
        setupJob = null
        sendJob?.cancel()
        sendJob = null
        val session = activeSession
        activeSession = null
        if (session != null) {
            scope.launch { captureService.cancelSession(session) }
        }
        route?.let { activeRoute -> scope.launch { activeRoute.output.releaseRoute() } }
        _status.value = WebhookStatus.Idle
    }

    private suspend fun startSession(route: ResolvedAudioRoute) {
        val channel = channelProvider()
        if (!channel.isReady) return

        runCatching {
            _status.value = WebhookStatus.WaitingForAudio
            val result = captureService.startSession(
                source = route.source,
                sco = route.sco,
                output = route.output,
                shouldProceed = { pttDown },
            )
            when (result) {
                CaptureStartResult.SessionActive -> _status.value = WebhookStatus.Error("Capture session already active")
                CaptureStartResult.ScoUnavailable -> _status.value = WebhookStatus.Error("SCO unavailable")
                CaptureStartResult.Cancelled -> _status.value = WebhookStatus.Cancelled
                CaptureStartResult.RecordingFailed -> _status.value = WebhookStatus.Error("Recording failed")
                is CaptureStartResult.Started -> {
                    activeSession = result.session
                    _status.value = WebhookStatus.Recording
                }
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            _status.value = WebhookStatus.Error(error.message ?: "Webhook capture failed")
            val session = activeSession
            activeSession = null
            if (session != null) captureService.cancelSession(session)
            route.output.releaseRoute()
        }
    }

    private suspend fun finishSessionIfNeeded(route: ResolvedAudioRoute) {
        val session = activeSession
        if (session == null) {
            setupJob?.cancel()
            setupJob = null
            if (_status.value != WebhookStatus.Idle && _status.value !is WebhookStatus.Error) {
                _status.value = WebhookStatus.Cancelled
            }
            return
        }
        activeSession = null
        val recording = session.stop()
        if (recording.isEmpty) {
            _status.value = WebhookStatus.EmptyAudio
            route.output.releaseRoute()
            return
        }

        _status.value = WebhookStatus.Transcribing
        val channel = channelProvider()
        sendJob = scope.launch { transcribeAndSend(recording, channel, route) }
    }

    private suspend fun transcribeAndSend(
        recording: RecordedPcm,
        channel: WebhookChannel,
        route: ResolvedAudioRoute,
    ) {
        try {
            val text = runCatching { transcriber.transcribe(recording.samples, recording.sampleRate) }
                .getOrElse { error ->
                    _status.value = WebhookStatus.Error(error.message ?: "Transcription failed")
                    return
                }
            if (text.isBlank()) {
                _status.value = WebhookStatus.EmptyAudio
                return
            }
            _status.value = WebhookStatus.Sending
            when (val result = client.send(renderWebhookRequest(channel, text))) {
                is WebhookDeliveryResult.Success -> _status.value = WebhookStatus.Sent(result.responseCode)
                is WebhookDeliveryResult.Failure -> _status.value = WebhookStatus.Error(result.reason)
            }
        } finally {
            route.output.releaseRoute()
        }
    }
}
