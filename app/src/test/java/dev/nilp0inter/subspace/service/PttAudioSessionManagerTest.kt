package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.AudioRouteEndpoint
import dev.nilp0inter.subspace.audio.CaptureServiceFakes
import dev.nilp0inter.subspace.audio.CaptureSource
import dev.nilp0inter.subspace.audio.ChannelAudioInputSession
import dev.nilp0inter.subspace.audio.ChannelInputResult
import dev.nilp0inter.subspace.audio.CaptureSourceId
import dev.nilp0inter.subspace.audio.OpenedCaptureSource
import dev.nilp0inter.subspace.audio.PcmOutput
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.audio.ResolvedAudioRoute
import dev.nilp0inter.subspace.audio.RouteGate
import dev.nilp0inter.subspace.audio.RouteGateResult
import dev.nilp0inter.subspace.audio.ScoRoute
import dev.nilp0inter.subspace.model.InputMode
import dev.nilp0inter.subspace.model.PttSource
import dev.nilp0inter.subspace.model.ScoState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PttAudioSessionManagerTest {
    @Test
    fun singleActiveSessionSpansRsmPhoneAndCarSources() = runTest {
        val fixture = Fixture(this)

        assertTrue(fixture.manager.start(PttSource.Rsm, "echo", InputMode.Work))
        runCurrent()
        assertFalse(fixture.manager.start(PttSource.Phone, "echo", InputMode.OnAPinch))

        fixture.manager.release(PttSource.Rsm)
        advanceUntilIdle()

        assertTrue(fixture.manager.start(PttSource.Phone, "echo", InputMode.OnAPinch))
        runCurrent()
        assertFalse(fixture.manager.start(PttSource.CarTelecom, "echo", InputMode.OnTheRoad))

        fixture.manager.release(PttSource.Phone)
        advanceUntilIdle()

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "echo", InputMode.OnTheRoad))
        runCurrent()
        fixture.manager.release(PttSource.CarTelecom)
        advanceUntilIdle()

        assertEquals(listOf("started", "released", "started", "released", "started", "released"), fixture.router.events)
    }

    @Test
    fun forceCancelReleasesActiveRouteExactlyOnce() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.Work)

        assertTrue(fixture.manager.start(PttSource.Rsm, "echo", InputMode.Work))
        runCurrent()
        fixture.manager.cancelActive("teardown")
        advanceUntilIdle()

        assertEquals(1, route.output.releaseRouteCount)
        assertEquals(listOf("started", "cancelled:teardown"), fixture.router.events)
        assertEquals(null, fixture.manager.activeSession)
    }

    @Test
    fun wrongSourceReleaseDoesNotClearActiveSession() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.Work)

        assertTrue(fixture.manager.start(PttSource.Rsm, "echo", InputMode.Work))
        runCurrent()
        fixture.manager.release(PttSource.Phone)
        advanceUntilIdle()

        assertEquals(PttSource.Rsm, fixture.manager.activeSession?.source)
        assertEquals(0, route.output.releaseRouteCount)

        fixture.manager.release(PttSource.Rsm)
        advanceUntilIdle()
        assertEquals(1, route.output.releaseRouteCount)
    }

    @Test
    fun staleOldSourceReleaseCannotClearNewerSession() = runTest {
        val fixture = Fixture(this)
        val oldRoute = fixture.route(InputMode.Work)
        val newRoute = fixture.route(InputMode.OnAPinch)

        assertTrue(fixture.manager.start(PttSource.Rsm, "echo", InputMode.Work))
        runCurrent()
        fixture.manager.release(PttSource.Rsm)
        advanceUntilIdle()
        assertEquals(1, oldRoute.output.releaseRouteCount)

        assertTrue(fixture.manager.start(PttSource.Phone, "echo", InputMode.OnAPinch))
        runCurrent()
        fixture.manager.release(PttSource.Rsm)
        advanceUntilIdle()

        assertEquals(PttSource.Phone, fixture.manager.activeSession?.source)
        assertEquals(0, newRoute.output.releaseRouteCount)
    }

    @Test
    fun channelReceivesInputSessionAndTerminalPcmWithoutRouteObjects() = runTest {
        val fixture = Fixture(this, pcm = shortArrayOf(10, 20, 30))

        assertTrue(fixture.manager.start(PttSource.Phone, "echo", InputMode.OnAPinch))
        runCurrent()
        fixture.manager.release(PttSource.Phone)
        advanceUntilIdle()

        assertEquals(16_000, fixture.router.startedSampleRates.single())
        assertEquals(listOf<Short>(10, 20, 30), fixture.router.recordings.single().samples.toList())
    }

    @Test
    fun releaseDuringSetupAfterAcquireReleasesScoExactlyOnce() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.Work)
        val acquireGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        route.sco.acquireGate = acquireGate

        assertTrue(fixture.manager.start(PttSource.Rsm, "echo", InputMode.Work))
        runCurrent()
        fixture.manager.release(PttSource.Rsm)
        acquireGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, route.sco.releaseCount)
        assertEquals(0, route.output.releaseRouteCount)
        assertEquals(null, fixture.manager.activeSession)
    }

    @Test
    fun pendingTelecomReservationBlocksPhoneAndRsmUntilCarStartsOrCancels() = runTest {
        val fixture = Fixture(this)

        assertTrue(fixture.manager.reservePending(PttSource.CarTelecom, "echo", InputMode.OnTheRoad))
        assertFalse(fixture.manager.start(PttSource.Phone, "echo", InputMode.OnAPinch))
        assertFalse(fixture.manager.start(PttSource.Rsm, "echo", InputMode.Work))

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "echo", InputMode.OnTheRoad))
        runCurrent()
        fixture.manager.cancelActive("timeout")
        advanceUntilIdle()

        assertEquals(null, fixture.manager.activeSession)
    }

    @Test
    fun routeGateTimeoutFailsClosedWithoutStartingCapture() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.OnTheRoad)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(9))
        route.source = source
        route.routeGate = RouteGate("release-work-before-car") {
            RouteGateResult.Timeout("Timed out waiting for target RSM route release")
        }

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "echo", InputMode.OnTheRoad))
        advanceUntilIdle()

        assertEquals(
            listOf("failed:Timed out waiting for target RSM route release"),
            fixture.router.events,
        )
        assertEquals("timeout must not be treated as capture readiness", 0, source.openCount)
        assertEquals(1, route.output.releaseRouteCount)
        assertEquals(null, fixture.manager.activeSession)
    }

    @Test
    fun routeGateCancellationReachesChannelOnlyAsCancellation() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.OnTheRoad)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(9))
        route.source = source
        route.routeGate = RouteGate("release-work-before-car") {
            RouteGateResult.Cancellation("stale Work route release cancelled")
        }

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "echo", InputMode.OnTheRoad))
        advanceUntilIdle()

        assertEquals(listOf("cancelled:stale Work route release cancelled"), fixture.router.events)
        assertEquals(0, source.openCount)
        assertEquals(1, route.output.releaseRouteCount)
    }

    @Test
    fun captureStartsOnlyAfterRouteGateAndRecorderOpenBothSucceed() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.OnTheRoad)
        val gate = CompletableDeferred<RouteGateResult>()
        val source = DeferredOpenCaptureSource(shortArrayOf(7, 8))
        route.routeGate = RouteGate("car-readiness") { gate.await() }
        route.source = source

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "echo", InputMode.OnTheRoad))
        runCurrent()
        assertEquals(emptyList<String>(), fixture.router.events)
        assertEquals(0, source.openCount)

        gate.complete(RouteGateResult.Success("car route ready"))
        runCurrent()
        assertEquals(emptyList<String>(), fixture.router.events)
        assertEquals(0, source.openCount)
        assertTrue(source.openStarted.isCompleted)

        source.allowOpen.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("started"), fixture.router.events)
        assertEquals(1, source.openCount)
        assertEquals(0, route.output.releaseRouteCount)
    }

    @Test
    fun captureStartupFailureAfterRouteReadinessReleasesRouteExactlyOnce() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.OnTheRoad)
        route.routeGate = RouteGate("car-readiness") {
            RouteGateResult.Success("car route ready")
        }
        route.source = CaptureServiceFakes.failingSource()

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "echo", InputMode.OnTheRoad))
        advanceUntilIdle()

        assertEquals(listOf("failed:Recording failed"), fixture.router.events)
        assertEquals(1, route.output.releaseRouteCount)
        assertEquals(null, fixture.manager.activeSession)
    }

    @Test
    fun warmWorkRouteCanBeReusedByConsecutiveWorkPtt() = runTest {
        val fixture = Fixture(this)
        val workRoute = fixture.route(InputMode.Work)

        assertTrue(fixture.manager.start(PttSource.Rsm, "echo", InputMode.Work))
        runCurrent()
        fixture.manager.release(PttSource.Rsm)
        advanceUntilIdle()

        assertTrue(fixture.manager.start(PttSource.Rsm, "echo", InputMode.Work))
        runCurrent()
        fixture.manager.release(PttSource.Rsm)
        advanceUntilIdle()

        assertEquals(listOf("started", "released", "started", "released"), fixture.router.events)
        assertEquals(2, workRoute.sco.acquireCount)
        assertEquals(2, workRoute.output.releaseRouteCount)
        assertEquals(0, fixture.route(InputMode.OnTheRoad).sco.acquireCount)
        assertEquals(0, fixture.route(InputMode.OnAPinch).sco.acquireCount)
    }

    @Test
    fun staleWorkRouteGateBlocksCarUntilObservedRelease() = runTest {
        val fixture = Fixture(this)
        val carRoute = fixture.route(InputMode.OnTheRoad)
        var workReleased = false
        carRoute.routeGate = RouteGate("release-work-before-car") {
            if (workReleased) {
                RouteGateResult.Success("target RSM released")
            } else {
                RouteGateResult.Timeout("Timed out waiting for target RSM route release")
            }
        }

        assertTrue(fixture.manager.start(PttSource.Rsm, "echo", InputMode.Work))
        runCurrent()
        fixture.manager.release(PttSource.Rsm)
        advanceUntilIdle()

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "echo", InputMode.OnTheRoad))
        advanceUntilIdle()
        assertEquals(
            listOf("started", "released", "failed:Timed out waiting for target RSM route release"),
            fixture.router.events,
        )
        assertEquals(0, carRoute.sco.acquireCount)
        assertEquals(1, carRoute.output.releaseRouteCount)

        workReleased = true
        assertTrue(fixture.manager.start(PttSource.CarTelecom, "echo", InputMode.OnTheRoad))
        advanceUntilIdle()

        assertEquals(
            listOf(
                "started",
                "released",
                "failed:Timed out waiting for target RSM route release",
                "started",
            ),
            fixture.router.events,
        )
        assertEquals(1, carRoute.sco.acquireCount)
    }

    @Test
    fun phoneCaptureAfterWarmWorkUsesLocalRouteWithoutChannelRoutePayload() = runTest {
        val fixture = Fixture(this)
        val localSource = CaptureServiceFakes.singleShotSource(
            shortArrayOf(4, 5, 6),
            sourceId = CaptureSourceId.Mic,
        )
        fixture.route(InputMode.OnAPinch).source = localSource

        assertTrue(fixture.manager.start(PttSource.Rsm, "echo", InputMode.Work))
        runCurrent()
        fixture.manager.release(PttSource.Rsm)
        advanceUntilIdle()

        assertTrue(fixture.manager.start(PttSource.Phone, "echo", InputMode.OnAPinch))
        runCurrent()
        fixture.manager.release(PttSource.Phone)
        advanceUntilIdle()

        assertEquals(listOf("started", "released", "started", "released"), fixture.router.events)
        assertEquals(1, localSource.openCount)
        assertEquals(listOf<Short>(4, 5, 6), fixture.router.recordings.last().samples.toList())
        assertEquals(0, fixture.route(InputMode.OnTheRoad).sco.acquireCount)
    }

    private class Fixture(
        scope: kotlinx.coroutines.test.TestScope,
        pcm: ShortArray = shortArrayOf(1, 2, 3),
    ) {
        val router = RecordingRouter()
        private val captureService = CaptureServiceFakes.newService(scope)
        private val routes = InputMode.entries.associateWith { mode ->
            TestRoute(
                sco = RecordingScoRoute(endpointFor(mode)),
                output = RecordingOutput(),
                source = CaptureServiceFakes.singleShotSource(
                    pcm,
                    sourceId = if (mode == InputMode.OnAPinch) CaptureSourceId.Mic else CaptureSourceId.VoiceCommunication,
                ),
            )
        }
        val manager = PttAudioSessionManager(
            scope = scope,
            captureService = captureService,
            channelRouter = router,
            resolvePttAudioRoute = { mode -> route(mode).resolved },
        )

        fun route(mode: InputMode): TestRoute = routes.getValue(mode)
    }

    private class RecordingRouter : ChannelRouter {
        val events = mutableListOf<String>()
        val startedSampleRates = mutableListOf<Int>()
        val recordings = mutableListOf<RecordedPcm>()

        override fun onInputStarted(channelId: String, session: ChannelAudioInputSession) {
            events += "started"
            startedSampleRates += session.sampleRate
        }

        override suspend fun onInputReleased(channelId: String, recording: RecordedPcm): ChannelInputResult {
            events += "released"
            recordings += recording
            return ChannelInputResult.None
        }

        override fun onInputCancelled(channelId: String, reason: String) {
            events += "cancelled:$reason"
        }

        override fun onInputFailed(channelId: String, reason: String) {
            events += "failed:$reason"
        }
    }

    private data class TestRoute(
        val sco: RecordingScoRoute,
        val output: RecordingOutput,
        var source: CaptureSource,
        var routeGate: RouteGate = RouteGate("test-open-route") {
            RouteGateResult.Success("test route open")
        },
    ) {
        val resolved: ResolvedAudioRoute
            get() = ResolvedAudioRoute(sco, output, source, sco.endpoint, routeGate)
    }

    private class RecordingScoRoute(
        override val endpoint: AudioRouteEndpoint,
    ) : ScoRoute {
        override val state: StateFlow<ScoState> = MutableStateFlow(ScoState.Inactive)
        override val coldStart: Boolean = false
        var acquireCount = 0
            private set
        var releaseCount = 0
            private set
        var acquireGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var acquireResult: Boolean = true

        override fun hasAvailableScoDevice(): Boolean = true
        override suspend fun acquire(): Boolean {
            acquireCount += 1
            acquireGate?.await()
            return acquireResult
        }
        override fun isActive(): Boolean = true
        override fun release() {
            releaseCount += 1
        }
    }

    private class RecordingOutput : PcmOutput {
        var releaseRouteCount = 0
            private set
        override suspend fun playReadyBeep(coldStart: Boolean) = Unit
        override suspend fun playErrorBeep(coldStart: Boolean) = Unit
        override suspend fun play(recording: RecordedPcm) = Unit
        override suspend fun releaseRoute() {
            releaseRouteCount += 1
        }
    }

    private class DeferredOpenCaptureSource(
        private val pcm: ShortArray,
    ) : CaptureSource {
        override val sourceId: CaptureSourceId = CaptureSourceId.VoiceCommunication
        val openStarted = CompletableDeferred<Unit>()
        val allowOpen = CompletableDeferred<Unit>()
        var openCount: Int = 0
            private set

        override suspend fun open(): OpenedCaptureSource? {
            openStarted.complete(Unit)
            allowOpen.await()
            openCount += 1
            return CaptureServiceFakes.singleShotSource(pcm).open()
        }
    }

    private companion object {
        fun endpointFor(mode: InputMode): AudioRouteEndpoint = when (mode) {
            InputMode.Work -> AudioRouteEndpoint.Rsm
            InputMode.OnAPinch -> AudioRouteEndpoint.Local
            InputMode.OnTheRoad -> AudioRouteEndpoint.Car
        }
    }
}
