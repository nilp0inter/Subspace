package dev.nilp0inter.subspace.webhook

import dev.nilp0inter.subspace.audio.CaptureService
import dev.nilp0inter.subspace.audio.CaptureServiceFakes
import dev.nilp0inter.subspace.audio.CaptureSource
import dev.nilp0inter.subspace.audio.PcmOutput
import dev.nilp0inter.subspace.audio.PcmTranscriber
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.audio.ResolvedAudioRoute
import dev.nilp0inter.subspace.audio.ScoRoute
import dev.nilp0inter.subspace.model.ScoState
import dev.nilp0inter.subspace.model.WebhookChannel
import dev.nilp0inter.subspace.model.WebhookStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebhookPttControllerTest {
    @Test
    fun pressReleaseTranscribesAndSendsWebhook() = runTest {
        val output = FakeOutput()
        val client = FakeWebhookClient(WebhookDeliveryResult.Success(202))
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3, 4))
        val controller = controller(
            scope = this,
            captureService = CaptureServiceFakes.newService(this),
            output = output,
            transcriber = FakeTranscriber("hello"),
            client = client,
        )

        controller.onPttPressed(route(output = output, source = source))
        runCurrent()
        controller.onPttReleased(route(output = output, source = source))
        advanceUntilIdle()

        assertEquals(WebhookStatus.Sent(202), controller.status.value)
        assertEquals("{\"message\":\"hello\"}", client.sent.single().body)
        assertEquals(1, output.releaseRouteCount)
    }

    @Test
    fun emptyAudioDoesNotTranscribeOrSend() = runTest {
        val client = FakeWebhookClient(WebhookDeliveryResult.Success(202))
        val transcriber = FakeTranscriber("ignored")
        val source = CaptureServiceFakes.emptySource()
        val controller = controller(
            scope = this,
            captureService = CaptureServiceFakes.newService(this),
            transcriber = transcriber,
            client = client,
        )

        controller.onPttPressed(route(source = source))
        runCurrent()
        controller.onPttReleased(route(source = source))
        advanceUntilIdle()

        assertEquals(WebhookStatus.EmptyAudio, controller.status.value)
        assertEquals(0, transcriber.callCount)
        assertEquals(emptyList<WebhookRequest>(), client.sent)
    }

    @Test
    fun transcriptionFailureDoesNotSendWebhook() = runTest {
        val client = FakeWebhookClient(WebhookDeliveryResult.Success(202))
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3, 4))
        val controller = controller(
            scope = this,
            captureService = CaptureServiceFakes.newService(this),
            transcriber = FakeTranscriber(error = IllegalStateException("stt failed")),
            client = client,
        )

        controller.onPttPressed(route(source = source))
        runCurrent()
        controller.onPttReleased(route(source = source))
        advanceUntilIdle()

        assertEquals(WebhookStatus.Error("stt failed"), controller.status.value)
        assertEquals(emptyList<WebhookRequest>(), client.sent)
    }

    @Test
    fun deliveryFailureSurfacesErrorWithoutRetry() = runTest {
        val client = FakeWebhookClient(WebhookDeliveryResult.Failure("HTTP 500"))
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3, 4))
        val controller = controller(
            scope = this,
            captureService = CaptureServiceFakes.newService(this),
            transcriber = FakeTranscriber("hello"),
            client = client,
        )

        controller.onPttPressed(route(source = source))
        runCurrent()
        controller.onPttReleased(route(source = source))
        advanceUntilIdle()

        assertEquals(WebhookStatus.Error("HTTP 500"), controller.status.value)
        assertEquals(1, client.sent.size)
    }

    private fun controller(
        scope: CoroutineScope,
        captureService: CaptureService,
        output: PcmOutput = FakeOutput(),
        transcriber: PcmTranscriber,
        client: WebhookClient,
    ): WebhookPttController = WebhookPttController(
        scope = scope,
        captureService = captureService,
        transcriber = transcriber,
        client = client,
        channelProvider = { WebhookChannel(url = "https://example.test/hook") },
    )

    private fun route(
        output: PcmOutput = FakeOutput(),
        source: CaptureSource = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3, 4)),
    ): ResolvedAudioRoute = ResolvedAudioRoute(FakeScoRoute(), output, source)

    private class FakeScoRoute : ScoRoute {
        private val _state = MutableStateFlow<ScoState>(ScoState.Inactive)
        override val state: StateFlow<ScoState> = _state
        override suspend fun acquire(): Boolean {
            _state.value = ScoState.Active
            return true
        }
        override fun hasAvailableScoDevice(): Boolean = true
        override fun isActive(): Boolean = _state.value == ScoState.Active
        override fun release() { _state.value = ScoState.Inactive }
    }

    private class FakeOutput : PcmOutput {
        var releaseRouteCount = 0; private set
        override suspend fun playReadyBeep(coldStart: Boolean) {}
        override suspend fun playErrorBeep(coldStart: Boolean) {}
        override suspend fun play(recording: RecordedPcm) {}
        override suspend fun releaseRoute() { releaseRouteCount += 1 }
    }

    private class FakeTranscriber(
        private val text: String = "",
        private val error: Throwable? = null,
    ) : PcmTranscriber {
        var callCount = 0; private set
        override suspend fun transcribe(pcm: ShortArray, sampleRate: Int): String {
            callCount += 1
            error?.let { throw it }
            return text
        }
    }

    private class FakeWebhookClient(private val result: WebhookDeliveryResult) : WebhookClient {
        val sent = mutableListOf<WebhookRequest>()
        override suspend fun send(request: WebhookRequest): WebhookDeliveryResult {
            sent += request
            return result
        }
    }
}
