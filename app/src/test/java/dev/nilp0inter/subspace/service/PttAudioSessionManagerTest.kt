package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.AudioRouteEndpoint
import dev.nilp0inter.subspace.audio.CaptureServiceFakes
import dev.nilp0inter.subspace.audio.CaptureSource
import dev.nilp0inter.subspace.audio.ChannelAudioInputSession
import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.audio.ChannelInputResult
import dev.nilp0inter.subspace.audio.ChannelInputTarget
import dev.nilp0inter.subspace.audio.CaptureSourceId
import dev.nilp0inter.subspace.audio.OpenedCaptureSource
import dev.nilp0inter.subspace.audio.PcmOutput
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.audio.ResolvedAudioRoute
import dev.nilp0inter.subspace.audio.RouteGate
import dev.nilp0inter.subspace.audio.RouteGateResult
import dev.nilp0inter.subspace.audio.ScoRoute
import dev.nilp0inter.subspace.audio.TelecomCapturePcmOutput
import dev.nilp0inter.subspace.audio.ResponsePlayer
import dev.nilp0inter.subspace.model.InputMode
import dev.nilp0inter.subspace.model.PttSource
import dev.nilp0inter.subspace.model.ScoState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
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
    fun normalCarReleaseNotifiesCompletionAfterCleanupWhenConnectionEndedArrivesEarly() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.OnTheRoad)
        val terminalGate = CompletableDeferred<Unit>()
        val terminalRecordings = mutableListOf<RecordedPcm>()
        var cancellationCount = 0
        val target = object : ChannelInputTarget {
            override fun onInputStarted(session: ChannelAudioInputSession) = Unit

            override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
                terminalRecordings += recording
                terminalGate.await()
                return ChannelInputResult.None
            }

            override fun onInputCancelled(reason: String) {
                cancellationCount += 1
            }

            override fun onInputFailed(reason: String) = Unit
        }
        fixture.router.acceptance = ChannelInputAcceptance.Accepted(target)

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "journal", InputMode.OnTheRoad))
        runCurrent()
        assertEquals(
            PttAudioSessionManager.SessionPhase.PttHeld,
            fixture.manager.activeSession?.phase,
        )

        fixture.manager.release(PttSource.CarTelecom)
        runCurrent()
        fixture.manager.cancelActive("connection-ended")
        runCurrent()
        assertEquals(
            PttAudioSessionManager.SessionPhase.TerminalWork,
            fixture.manager.activeSession?.phase,
        )

        assertEquals(1, terminalRecordings.size)
        assertEquals(listOf<Short>(1, 2, 3), terminalRecordings.single().samples.toList())
        assertEquals(0, cancellationCount)
        assertEquals(0, route.output.releaseRouteCount)
        assertTrue(fixture.terminalCompletionObservations.isEmpty())

        terminalGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, terminalRecordings.size)
        assertEquals(0, cancellationCount)
        assertEquals(1, route.output.releaseRouteCount)
        assertEquals(null, fixture.manager.activeSession)
        val completion = fixture.terminalCompletionObservations.single()
        assertEquals(InputMode.OnTheRoad, completion.mode)
        assertEquals(null, completion.activeSession)
        assertEquals(1, completion.routeReleaseCount)
    }

    @Test
    fun forceCancelDuringResolvedRouteGateReleasesTelecomOutputExactlyOnce() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.OnTheRoad)
        val gate = CompletableDeferred<RouteGateResult>()
        route.routeGate = RouteGate("car-readiness") { gate.await() }

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "journal", InputMode.OnTheRoad))
        runCurrent()
        fixture.manager.cancelActive("connection-ended")
        gate.complete(RouteGateResult.Success("car route ready"))
        advanceUntilIdle()

        assertEquals(1, route.output.releaseRouteCount)
        assertTrue(fixture.router.events.isEmpty())
        assertEquals(null, fixture.manager.activeSession)
    }

    @Test
    fun forceCancelDuringRecorderPreflightReleasesTelecomOutputExactlyOnce() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.OnTheRoad)
        val source = DeferredOpenCaptureSource(shortArrayOf(11, 12))
        route.source = source

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "journal", InputMode.OnTheRoad))
        runCurrent()
        assertTrue(source.openStarted.isCompleted)

        fixture.manager.cancelActive("connection-ended")
        source.allowOpen.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, route.output.releaseRouteCount)
        assertEquals(listOf("cancelled:Cancelled"), fixture.router.events)
        assertEquals(null, fixture.manager.activeSession)
    }

    @Test
    fun forceCancelDuringReadyBeepReleasesTelecomOutputExactlyOnce() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.OnTheRoad)
        val beepStarted = CompletableDeferred<Unit>()
        val allowBeep = CompletableDeferred<Unit>()
        route.output.readyBeepStarted = beepStarted
        route.output.readyBeepGate = allowBeep

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "journal", InputMode.OnTheRoad))
        runCurrent()
        assertTrue(beepStarted.isCompleted)

        fixture.manager.cancelActive("connection-ended")
        allowBeep.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, route.output.releaseRouteCount)
        assertEquals(listOf("cancelled:Cancelled"), fixture.router.events)
        assertEquals(null, fixture.manager.activeSession)
    }

    @Test
    fun shortCarPressDuringRecorderPreflightReleasesTelecomOutputOnceWithoutVisiblePcm() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.OnTheRoad)
        val source = DeferredOpenCaptureSource(shortArrayOf(21, 22))
        route.source = source

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "journal", InputMode.OnTheRoad))
        runCurrent()
        fixture.manager.release(PttSource.CarTelecom)
        source.allowOpen.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, route.output.releaseRouteCount)
        assertTrue(fixture.router.events.none { it == "started" || it == "released" })
        assertTrue(fixture.router.liveFrames.isEmpty())
        assertTrue(fixture.router.recordings.isEmpty())
    }

    @Test
    fun shortCarPressDuringReadyBeepReleasesTelecomOutputOnceWithoutVisiblePcm() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.OnTheRoad)
        val beepStarted = CompletableDeferred<Unit>()
        val allowBeep = CompletableDeferred<Unit>()
        route.output.readyBeepStarted = beepStarted
        route.output.readyBeepGate = allowBeep

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "journal", InputMode.OnTheRoad))
        runCurrent()
        assertTrue(beepStarted.isCompleted)

        fixture.manager.release(PttSource.CarTelecom)
        allowBeep.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, route.output.releaseRouteCount)
        assertTrue(fixture.router.events.none { it == "started" || it == "released" })
        assertTrue(fixture.router.liveFrames.isEmpty())
        assertTrue(fixture.router.recordings.isEmpty())
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

        fixture.manager.release(PttSource.Phone)
        advanceUntilIdle()
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

        assertEquals(emptyList<String>(), fixture.router.events)
        assertEquals(0, route.output.readyBeepCount)
        assertEquals(1, route.output.errorBeepCount)
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

        assertEquals(emptyList<String>(), fixture.router.events)
        assertEquals(0, source.openCount)
        assertEquals(1, route.output.releaseRouteCount)
        assertEquals(0, route.output.readyBeepCount)
        assertEquals(1, route.output.errorBeepCount)
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
        assertEquals(
            listOf("prepare:echo", "ready:Car", "started:echo"),
            fixture.timeline,
        )
        fixture.manager.release(PttSource.CarTelecom)
        advanceUntilIdle()
    }

    @Test
    fun channelRefusalPlaysProblemBeepWithoutReadyBeepOrCapture() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.OnTheRoad)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(9))
        route.source = source
        fixture.router.acceptance = ChannelInputAcceptance.Refused("Journal base directory unavailable")

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "journal", InputMode.OnTheRoad))
        advanceUntilIdle()

        assertEquals(emptyList<String>(), fixture.router.events)
        assertEquals(0, source.openCount)
        assertEquals(0, route.output.readyBeepCount)
        assertEquals(1, route.output.errorBeepCount)
        assertEquals(1, route.output.releaseRouteCount)
        assertEquals(null, fixture.manager.activeSession)
    }

    @Test
    fun committedCarRoutePlaysReadyBeforeChannelVisibleStart() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.OnTheRoad)

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "journal", InputMode.OnTheRoad))
        advanceUntilIdle()

        assertEquals(
            listOf("prepare:journal", "ready:Car", "started:journal"),
            fixture.timeline,
        )
        assertEquals(1, route.output.readyBeepCount)
        assertEquals(0, route.output.errorBeepCount)

        fixture.manager.release(PttSource.CarTelecom)
        advanceUntilIdle()
    }

    @Test
    fun terminalReleaseUsesOriginallyCommittedTarget() = runTest {
        val fixture = Fixture(this)
        val targetEvents = mutableListOf<String>()
        val originalTarget = object : ChannelInputTarget {
            override fun onInputStarted(session: ChannelAudioInputSession) {
                targetEvents += "original-started"
            }

            override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
                targetEvents += "original-released"
                return ChannelInputResult.None
            }

            override fun onInputCancelled(reason: String) {
                targetEvents += "original-cancelled"
            }

            override fun onInputFailed(reason: String) {
                targetEvents += "original-failed"
            }
        }
        val replacementTarget = object : ChannelInputTarget {
            override fun onInputStarted(session: ChannelAudioInputSession) {
                targetEvents += "replacement-started"
            }

            override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
                targetEvents += "replacement-released"
                return ChannelInputResult.None
            }

            override fun onInputCancelled(reason: String) {
                targetEvents += "replacement-cancelled"
            }

            override fun onInputFailed(reason: String) {
                targetEvents += "replacement-failed"
            }
        }
        fixture.router.acceptance = ChannelInputAcceptance.Accepted(originalTarget)

        assertTrue(fixture.manager.start(PttSource.Phone, "debug", InputMode.OnAPinch))
        runCurrent()
        fixture.router.acceptance = ChannelInputAcceptance.Accepted(replacementTarget)
        fixture.manager.release(PttSource.Phone)
        advanceUntilIdle()

        assertEquals(listOf("original-started", "original-released"), targetEvents)
    }

    @Test
    fun postReadyLivePcmReachesCommittedTarget() = runTest {
        val fixture = Fixture(this)
        val source = GatedFrameCaptureSource(shortArrayOf(42, 43))
        fixture.route(InputMode.OnAPinch).source = source

        assertTrue(fixture.manager.start(PttSource.Phone, "debug", InputMode.OnAPinch))
        runCurrent()
        assertEquals(listOf("prepare:debug", "ready:Local", "started:debug"), fixture.timeline)

        source.allowFrames = true
        advanceTimeBy(2)
        runCurrent()

        assertEquals(listOf<Short>(42, 43), fixture.router.liveFrames.single().toList())

        fixture.manager.release(PttSource.Phone)
        advanceUntilIdle()
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
        assertEquals(0, route.output.readyBeepCount)
        assertEquals(1, route.output.errorBeepCount)
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
            listOf("started", "released"),
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
                "started",
            ),
            fixture.router.events,
        )
        assertEquals(1, carRoute.sco.acquireCount)
        fixture.manager.release(PttSource.CarTelecom)
        advanceUntilIdle()
    }

    @Test
    fun phoneCaptureAfterWarmWorkUsesLocalRouteWithoutChannelRoutePayload() = runTest {
        val fixture = Fixture(this)
        val localSource = ReadyAfterBeepCaptureSource(
            pcm = shortArrayOf(4, 5, 6),
            output = fixture.route(InputMode.OnAPinch).output,
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
    @Test
    fun activeCarPlaybackReleasesTelecomRouteOnceBeforeMediaPlayback() = runTest {
        val events = mutableListOf<String>()
        val captureOutput = RecordingOutput(mutableListOf(), AudioRouteEndpoint.Car)
        val telecomOutput = TelecomCapturePcmOutput(
            captureOutput = captureOutput,
            mediaResponsePlayer = object : ResponsePlayer {
                override suspend fun play(recording: RecordedPcm) {
                    events += "media-playback"
                }
            },
            releaseCaptureRoute = { events += "telecom-release" },
            awaitTelecomDisconnected = { events += "disconnect-wait" },
        )
        val target = object : ChannelInputTarget {
            override fun onInputStarted(session: ChannelAudioInputSession) = Unit

            override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
                events += "channel-release"
                return ChannelInputResult.Playback(recording)
            }

            override fun onInputPlaybackCompleted() {
                events += "channel-playback-complete"
            }

            override fun onInputCancelled(reason: String) {
                events += "channel-cancelled"
            }

            override fun onInputFailed(reason: String) {
                events += "channel-failed"
            }
        }
        val manager = PttAudioSessionManager(
            scope = this,
            captureService = CaptureServiceFakes.newService(this),
            channelRouter = object : ChannelRouter {
                override fun prepareInput(channelId: String): ChannelInputAcceptance =
                    ChannelInputAcceptance.Accepted(target)
            },
            resolvePttAudioRoute = {
                ResolvedAudioRoute(
                    sco = RecordingScoRoute(AudioRouteEndpoint.Car),
                    output = telecomOutput,
                    source = ReadyAfterBeepCaptureSource(
                        pcm = shortArrayOf(31, 32),
                        output = captureOutput,
                        sourceId = CaptureSourceId.VoiceCommunication,
                    ),
                    endpoint = AudioRouteEndpoint.Car,
                )
            },
            onTerminalCompleted = { events += "manager-terminal" },
        )

        assertTrue(manager.start(PttSource.CarTelecom, "echo", InputMode.OnTheRoad))
        runCurrent()
        assertEquals(PttAudioSessionManager.SessionPhase.PttHeld, manager.activeSession?.phase)

        manager.release(PttSource.CarTelecom)
        advanceUntilIdle()

        assertEquals(
            listOf(
                "channel-release",
                "telecom-release",
                "disconnect-wait",
                "media-playback",
                "channel-playback-complete",
                "manager-terminal",
            ),
            events,
        )
        assertEquals(null, manager.activeSession)
    }

    private data class TerminalCompletionObservation(
        val activeSession: PttAudioSessionManager.SessionSnapshot?,
        val routeReleaseCount: Int,
        val mode: InputMode,
    )

    private class Fixture(
        scope: kotlinx.coroutines.test.TestScope,
        pcm: ShortArray = shortArrayOf(1, 2, 3),
    ) {
        val timeline = mutableListOf<String>()
        val terminalCompletionObservations = mutableListOf<TerminalCompletionObservation>()
        val router = RecordingRouter(scope, timeline)
        private val captureService = CaptureServiceFakes.newService(scope)
        private val routes = InputMode.entries.associateWith { mode ->
            val output = RecordingOutput(timeline, endpointFor(mode))
            TestRoute(
                sco = RecordingScoRoute(endpointFor(mode)),
                output = output,
                source = ReadyAfterBeepCaptureSource(
                    pcm = pcm,
                    output = output,
                    sourceId = if (mode == InputMode.OnAPinch) CaptureSourceId.Mic else CaptureSourceId.VoiceCommunication,
                ),
            )
        }
        lateinit var manager: PttAudioSessionManager
            private set

        init {
            manager = PttAudioSessionManager(
                scope = scope,
                captureService = captureService,
                channelRouter = router,
                resolvePttAudioRoute = { mode -> route(mode).resolved },
                onTerminalCompleted = { completion ->
                    terminalCompletionObservations += TerminalCompletionObservation(
                        activeSession = manager.activeSession,
                        mode = completion.mode,
                        routeReleaseCount = route(completion.mode).output.releaseRouteCount,
                    )
                },
            )
        }

        fun route(mode: InputMode): TestRoute = routes.getValue(mode)
    }

    private class RecordingRouter(
        private val scope: kotlinx.coroutines.test.TestScope,
        private val timeline: MutableList<String>,
    ) : ChannelRouter {
        val events = mutableListOf<String>()
        val startedSampleRates = mutableListOf<Int>()
        val recordings = mutableListOf<RecordedPcm>()
        val liveFrames = mutableListOf<ShortArray>()
        private val liveJobs = mutableListOf<Job>()
        var acceptance: ChannelInputAcceptance? = null
        var includeChannelIds: Boolean = false

        override fun prepareInput(channelId: String): ChannelInputAcceptance {
            timeline += "prepare:$channelId"
            return acceptance ?: ChannelInputAcceptance.Accepted(RecordingTarget(channelId))
        }

        private inner class RecordingTarget(
            private val channelId: String,
        ) : ChannelInputTarget {
            override fun onInputStarted(session: ChannelAudioInputSession) {
                events += event("started", channelId)
                timeline += "started:$channelId"
                startedSampleRates += session.sampleRate
                liveJobs += scope.launch {
                    session.frames.collect { chunk -> liveFrames += chunk }
                }
            }

            override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
                events += event("released", channelId)
                timeline += "released:$channelId"
                recordings += recording
                liveJobs.forEach { it.cancel() }
                liveJobs.clear()
                return ChannelInputResult.None
            }

            override fun onInputCancelled(reason: String) {
                events += event("cancelled:$reason", channelId)
                liveJobs.forEach { it.cancel() }
                liveJobs.clear()
            }

            override fun onInputFailed(reason: String) {
                events += event("failed:$reason", channelId)
                liveJobs.forEach { it.cancel() }
                liveJobs.clear()
            }
        }

        private fun event(name: String, channelId: String): String =
            if (includeChannelIds) "$name:$channelId" else name
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

    private class RecordingOutput(
        private val timeline: MutableList<String>,
        private val endpoint: AudioRouteEndpoint,
    ) : PcmOutput {
        var releaseRouteCount = 0
            private set
        var readyBeepCount = 0
            private set
        var errorBeepCount = 0
            private set
        var readyBeepStarted: CompletableDeferred<Unit>? = null
        var readyBeepGate: CompletableDeferred<Unit>? = null
        var readyBeepCompleted: Boolean = false
            private set
        override suspend fun playReadyBeep(coldStart: Boolean) {
            readyBeepCount += 1
            readyBeepCompleted = false
            timeline += "ready:$endpoint"
            readyBeepStarted?.complete(Unit)
            readyBeepGate?.await()
            readyBeepCompleted = true
        }
        override suspend fun playErrorBeep(coldStart: Boolean) {
            errorBeepCount += 1
            timeline += "problem:$endpoint"
        }
        override suspend fun play(recording: RecordedPcm) = Unit
        override suspend fun releaseRoute() {
            releaseRouteCount += 1
        }
    }
    private class ReadyAfterBeepCaptureSource(
        private val pcm: ShortArray,
        private val output: RecordingOutput,
        override val sourceId: CaptureSourceId,
    ) : CaptureSource {
        var openCount: Int = 0
            private set

        override suspend fun open(): OpenedCaptureSource? {
            openCount += 1
            val delegate = CaptureServiceFakes.singleShotSource(
                pcm,
                sourceId = sourceId,
            ).open() ?: return null
            return object : OpenedCaptureSource {
                override val sampleRate: Int = delegate.sampleRate
                override val bufferSizeShorts: Int = delegate.bufferSizeShorts

                override fun read(buffer: ShortArray): Int =
                    if (output.readyBeepCompleted) delegate.read(buffer) else 0

                override fun close() = delegate.close()
            }
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

    private class GatedFrameCaptureSource(
        private val pcm: ShortArray,
    ) : CaptureSource {
        override val sourceId: CaptureSourceId = CaptureSourceId.Mic
        var allowFrames: Boolean = false
        var openCount: Int = 0
            private set

        override suspend fun open(): OpenedCaptureSource? {
            openCount += 1
            return object : OpenedCaptureSource {
                private var delivered = false
                override val sampleRate: Int = 16_000
                override val bufferSizeShorts: Int = pcm.size.coerceAtLeast(1)

                override fun read(buffer: ShortArray): Int {
                    if (!allowFrames || delivered) return 0
                    delivered = true
                    pcm.copyInto(buffer)
                    return pcm.size
                }

                override fun close() = Unit
            }
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
