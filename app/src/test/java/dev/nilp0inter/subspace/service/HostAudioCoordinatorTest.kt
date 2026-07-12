package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.AcquiredPlaybackRoute
import dev.nilp0inter.subspace.audio.ActivePcmPlayback
import dev.nilp0inter.subspace.audio.AudioRouteEndpoint
import dev.nilp0inter.subspace.audio.PlaybackCompletion
import dev.nilp0inter.subspace.audio.PlaybackRouteAcquisition
import dev.nilp0inter.subspace.audio.PlaybackRouteStrategy
import dev.nilp0inter.subspace.audio.RecordedPcm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavior tests for the process-wide half-duplex audio admission owner.
 *
 * These tests exercise the admission races, terminal ownership, and cue-debounce
 * propagation without any Android audio object: [PlaybackRouteStrategy] and
 * [ActivePcmPlayback] are faked so the coordinator's state machine is the only
 * subject.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostAudioCoordinatorTest {

    @Test
    fun reserveCaptureFromIdleGrantsLeaseAndOwensAdmission() {
        val coordinator = HostAudioCoordinator()

        val admission = coordinator.reserveCapture()

        assertTrue(admission is HostCaptureAdmission.Granted)
        val lease = (admission as HostCaptureAdmission.Granted).lease
        assertNotNull(lease.operationId)
        assertTrue(lease.operationId.value.isNotBlank())

        // A second reservation while the first capture lease is outstanding must be Busy.
        val second = coordinator.reserveCapture()
        assertEquals(HostCaptureAdmission.Busy, second)
    }

    @Test
    fun reserveCaptureDuringActivePlaybackIsRejectedAndLatchesRejectedPressPendingRelease() = runTest {
        val coordinator = HostAudioCoordinator()
        val playback = ControllablePlayback()
        val strategy = FakeRouteStrategy(FakeAcquiredRoute(playback))

        val playJob = async {
            coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        }
        playback.started.await()

        // A PTT press during active playback must be rejected.
        val admission = coordinator.reserveCapture()
        assertTrue(admission is HostCaptureAdmission.RejectedByPlayback)

        // The coordinator latches a pending release for the rejected press.
        assertTrue(coordinator.consumeRejectedPttRelease())

        // A second consume returns false: the latch is single-shot.
        assertFalse(coordinator.consumeRejectedPttRelease())

        // Complete playback to clean up.
        playback.complete(PlaybackCompletion.Completed)
        playJob.await()
    }

    @Test
    fun commitCaptureWithMatchingLeaseSucceedsButMismatchedLeaseFails() {
        val coordinator = HostAudioCoordinator()

        val lease = coordinator.reserveCapture() as HostCaptureAdmission.Granted
        assertTrue(coordinator.commitCapture(lease.lease))
        // Idempotent commit is allowed.
        assertTrue(coordinator.commitCapture(lease.lease))

        // A fabricated lease must not commit.
        val foreignLease = HostCaptureLease(HostAudioOperationId("foreign"))
        assertFalse(coordinator.commitCapture(foreignLease))
    }

    @Test
    fun releaseCaptureClearsOwnershipAndAllowsSubsequentPlayback() = runTest {
        val coordinator = HostAudioCoordinator()

        val lease = coordinator.reserveCapture() as HostCaptureAdmission.Granted
        assertTrue(coordinator.releaseCapture(lease.lease))

        // After release, playback must be admitted.
        val playback = ControllablePlayback()
        val strategy = FakeRouteStrategy(FakeAcquiredRoute(playback))
        val deferred = async {
            coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        }
        playback.started.await()
        playback.complete(PlaybackCompletion.Completed)
        assertEquals(HostPlaybackResult.Completed, deferred.await())
    }

    @Test
    fun releaseCaptureWithMismatchedLeaseIsIgnoredAndKeepsOwnership() {
        val coordinator = HostAudioCoordinator()

        val lease = coordinator.reserveCapture() as HostCaptureAdmission.Granted
        val foreignLease = HostCaptureLease(HostAudioOperationId("foreign"))
        assertFalse(coordinator.releaseCapture(foreignLease))

        // The original lease still owns; a new reserve must still be Busy.
        assertEquals(HostCaptureAdmission.Busy, coordinator.reserveCapture())
    }

    @Test
    fun playFromIdleAcquiresRouteAndReturnsCompletionResult() = runTest {
        val coordinator = HostAudioCoordinator()
        val playback = ControllablePlayback()
        val route = FakeAcquiredRoute(playback)
        val strategy = FakeRouteStrategy(route)

        val deferred = async {
            coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        }
        playback.started.await()
        playback.complete(PlaybackCompletion.Completed)

        assertEquals(HostPlaybackResult.Completed, deferred.await())
        assertTrue(route.released)
        assertEquals(1, route.releaseCount)
    }

    @Test
    fun playWhileCaptureOwnsAdmissionReturnsBusyWithoutAcquiringRoute() = runTest {
        val coordinator = HostAudioCoordinator()
        coordinator.reserveCapture()
        val route = FakeAcquiredRoute(ControllablePlayback())
        val strategy = FakeRouteStrategy(route)

        val result = coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        assertEquals(HostPlaybackResult.Busy, result)
        assertFalse(strategy.acquired)
        assertEquals(0, route.releaseCount)
    }

    @Test
    fun playWhenRouteAcquisitionIsUnavailableReturnsUnavailableAndReleasesReservation() = runTest {
        val coordinator = HostAudioCoordinator()
        val strategy = FakeRouteStrategy(failAcquire = PlaybackRouteAcquisition.Unavailable("no device"))

        val result = coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        assertTrue(result is HostPlaybackResult.Unavailable)
        assertEquals("no device", (result as HostPlaybackResult.Unavailable).reason)

        // After the failed attempt, admission must be free again.
        assertTrue(coordinator.reserveCapture() is HostCaptureAdmission.Granted)
    }

    @Test
    fun playWhenRouteAcquisitionFailsReturnsFailedAndReleasesReservation() = runTest {
        val coordinator = HostAudioCoordinator()
        val strategy = FakeRouteStrategy(failAcquire = PlaybackRouteAcquisition.Failed("route error"))

        val result = coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        assertTrue(result is HostPlaybackResult.Failed)

        // Admission is free again.
        assertTrue(coordinator.reserveCapture() is HostCaptureAdmission.Granted)
    }

    @Test
    fun playWhenRouteStartThrowsReturnsFailedAndReleasesRoute() = runTest {
        val coordinator = HostAudioCoordinator()
        val route = FakeAcquiredRoute(ControllablePlayback(), throwOnStart = true)
        val strategy = FakeRouteStrategy(route)

        val result = coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        assertTrue(result is HostPlaybackResult.Failed)
        assertTrue(route.released)
        assertEquals(1, route.releaseCount)
    }

    @Test
    fun playbackThatIsExplicitlySkippedReturnsExplicitlySkipped() = runTest {
        val coordinator = HostAudioCoordinator()
        val playback = ControllablePlayback()
        val route = FakeAcquiredRoute(playback)
        val strategy = FakeRouteStrategy(route)

        val deferred = async {
            coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        }
        playback.started.await()
        playback.complete(PlaybackCompletion.ExplicitlySkipped)

        assertEquals(HostPlaybackResult.ExplicitlySkipped, deferred.await())
        assertTrue(route.released)
    }

    @Test
    fun playbackThatIsInterruptedReturnsInterrupted() = runTest {
        val coordinator = HostAudioCoordinator()
        val playback = ControllablePlayback()
        val route = FakeAcquiredRoute(playback)
        val strategy = FakeRouteStrategy(route)

        val deferred = async {
            coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        }
        playback.started.await()
        playback.complete(PlaybackCompletion.Interrupted)

        assertEquals(HostPlaybackResult.Interrupted, deferred.await())
        assertTrue(route.released)
    }

    @Test
    fun consumeSosDuringPlaybackSkipsActivePlaybackAndReturnsConsumedByPlayback() = runTest {
        val coordinator = HostAudioCoordinator()
        val playback = ControllablePlayback()
        val route = FakeAcquiredRoute(playback)
        val strategy = FakeRouteStrategy(route)

        val deferred = async {
            coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        }
        playback.started.await()

        val disposition = coordinator.consumeSosDuringPlayback()
        assertEquals(HostSosDisposition.ConsumedByPlayback, disposition)
        assertTrue(playback.skipRequested)

        // The skip propagates as ExplicitlySkipped through the coordinator.
        assertEquals(HostPlaybackResult.ExplicitlySkipped, deferred.await())
    }

    @Test
    fun consumeSosDuringPlaybackWhenIdleDispatchesToChannel() = runTest {
        val coordinator = HostAudioCoordinator()
        assertEquals(HostSosDisposition.DispatchToChannel, coordinator.consumeSosDuringPlayback())
    }

    @Test
    fun consumeSosDuringPlaybackWhenCaptureOwnsDispatchesToChannel() = runTest {
        val coordinator = HostAudioCoordinator()
        coordinator.reserveCapture()
        assertEquals(HostSosDisposition.DispatchToChannel, coordinator.consumeSosDuringPlayback())
    }

    @Test
    fun consumeSosDuringPlaybackIsIdempotentBeforeSkipCompletes() = runTest {
        val coordinator = HostAudioCoordinator()
        val playback = ControllablePlayback()
        val route = FakeAcquiredRoute(playback)
        val strategy = FakeRouteStrategy(route)

        val deferred = async {
            coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        }
        playback.started.await()

        // First SOS is consumed by playback.
        assertEquals(HostSosDisposition.ConsumedByPlayback, coordinator.consumeSosDuringPlayback())

        // A second SOS before the skip completes still returns ConsumedByPlayback:
        // the playback owner has not released yet (the skip completion has not propagated).
        assertEquals(HostSosDisposition.ConsumedByPlayback, coordinator.consumeSosDuringPlayback())

        // The skip from the first SOS already completed the playback.
        deferred.await()
    }

    @Test
    fun closeReleasesActivePlaybackAndRejectsFutureAdmission() = runTest {
        val coordinator = HostAudioCoordinator()
        val playback = ControllablePlayback()
        val route = FakeAcquiredRoute(playback)
        val strategy = FakeRouteStrategy(route)

        val deferred = async {
            coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        }
        playback.started.await()

        coordinator.close()
        assertTrue(playback.skipRequested)

        // After close, both capture and playback are rejected.
        assertEquals(HostCaptureAdmission.Closed, coordinator.reserveCapture())
        assertEquals(HostPlaybackResult.Closed, coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy })

        // The play that was interrupted by close resolves.
        // The play that was interrupted by close resolves as ExplicitlySkipped:
        // close() skips the playback, and the pump reports the skip completion.
        assertEquals(HostPlaybackResult.ExplicitlySkipped, deferred.await())
    }

    @Test
    fun pttRejectedDuringPlaybackTriggersToneExactlyOnce() = runTest {
        val coordinator = HostAudioCoordinator()
        val playback = ControllablePlayback()
        val route = FakeAcquiredRoute(playback)
        val strategy = FakeRouteStrategy(route)

        val deferred = async {
            coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        }
        playback.started.await()

        // First rejected PTT press triggers the tone.
        val first = coordinator.reserveCapture()
        assertTrue(first is HostCaptureAdmission.RejectedByPlayback)
        assertTrue(playback.toneRejected)

        // A second PTT press while the first tone is still latched does not queue a second tone.
        playback.toneRejected = false
        val second = coordinator.reserveCapture()
        assertTrue(second is HostCaptureAdmission.RejectedByPlayback)
        assertFalse(playback.toneRejected)

        playback.complete(PlaybackCompletion.Completed)
        deferred.await()
    }

    @Test
    fun closeWhileCaptureLeaseOutstandingRejectsFutureReservations() = runTest {
        val coordinator = HostAudioCoordinator()
        coordinator.reserveCapture()

        coordinator.close()

        assertEquals(HostCaptureAdmission.Closed, coordinator.reserveCapture())
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    internal class FakeRouteStrategy(
        private val route: AcquiredPlaybackRoute? = null,
        private val failAcquire: PlaybackRouteAcquisition? = null,
    ) : PlaybackRouteStrategy {
        var acquired = false
            private set

        override suspend fun acquire(): PlaybackRouteAcquisition {
            acquired = true
            failAcquire?.let { return it }
            return PlaybackRouteAcquisition.Acquired(route!!)
        }
    }

    internal class FakeAcquiredRoute(
        private val playback: ActivePcmPlayback,
        private val throwOnStart: Boolean = false,
    ) : AcquiredPlaybackRoute {
        override val endpoint: AudioRouteEndpoint = AudioRouteEndpoint.Unspecified

        var released = false
            private set
        var releaseCount = 0
            private set

        override suspend fun start(recording: RecordedPcm): ActivePcmPlayback {
            if (throwOnStart) throw IllegalStateException("start failed")
            return playback
        }

        override suspend fun release() {
            released = true
            releaseCount += 1
        }
    }

    /**
     * A controllable [ActivePcmPlayback] whose completion is driven by the test
     * or by [skip]. [skip] completes the deferred with [PlaybackCompletion.ExplicitlySkipped],
     * mirroring the real pump-loop behavior where a skip request causes the stream
     * to terminate as explicitly skipped.
     */
    internal class ControllablePlayback : ActivePcmPlayback {
        val started = CompletableDeferred<Unit>()
        private val completion = CompletableDeferred<PlaybackCompletion>()
        private val toneSlot = java.util.concurrent.atomic.AtomicBoolean(false)

        var toneRejected = false
        var skipRequested = false
            private set

        fun complete(result: PlaybackCompletion) {
            started.complete(Unit)
            completion.complete(result)
        }

        override suspend fun awaitCompletion(): PlaybackCompletion {
            started.complete(Unit)
            return completion.await()
        }

        override fun rejectPttWithTone(): Boolean {
            // Single-slot debounce: at most one tone is latched at a time, mirroring
            // ActiveStreamPcmPlayback.toneSlot.
            if (!completion.isActive) return false
            val latched = toneSlot.compareAndSet(false, true)
            if (latched) toneRejected = true
            return latched
        }

        override fun skip(): Boolean {
            if (!completion.isActive) return false
            if (skipRequested) return false
            skipRequested = true
            // Skip wins; cancel any latched tone (mirrors ActiveStreamPcmPlayback).
            toneSlot.set(false)
            // Mirror the real pump: skip terminates the stream as explicitly skipped.
            started.complete(Unit)
            completion.complete(PlaybackCompletion.ExplicitlySkipped)
            return true
        }
    }
}