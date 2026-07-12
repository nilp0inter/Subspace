package dev.nilp0inter.subspace.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavior tests for the [PlaybackRouteStrategy] / [AcquiredPlaybackRoute] abstraction,
 * covering acquisition outcomes (acquired, busy, unavailable, failed), route release
 * stability (exactly-once), and start/release lifecycle without any Android audio object.
 *
 * [ModePlaybackRouteResolver] itself requires [android.media.AudioManager] and
 * [ScoAudioController] and is therefore not instantiated here. These tests verify
 * the generic contracts that the resolver and [HostAudioCoordinator] depend on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackRouteStrategyTest {

    @Test
    fun strategyAcquiringAnAcquiredRouteReturnsThatRoute() = runTest {
        val route = FakeAcquiredRoute()
        val strategy = PlaybackRouteStrategy { PlaybackRouteAcquisition.Acquired(route) }

        val acquisition = strategy.acquire()
        assertTrue(acquisition is PlaybackRouteAcquisition.Acquired)
        assertSame(route, (acquisition as PlaybackRouteAcquisition.Acquired).route)
    }

    @Test
    fun strategyReturningBusyDoesNotCarryARoute() = runTest {
        val strategy = PlaybackRouteStrategy { PlaybackRouteAcquisition.Busy }
        val acquisition = strategy.acquire()
        assertEquals(PlaybackRouteAcquisition.Busy, acquisition)
    }

    @Test
    fun strategyReturningUnavailableCarriesAReason() = runTest {
        val strategy = PlaybackRouteStrategy { PlaybackRouteAcquisition.Unavailable("SCO transport unavailable") }
        val acquisition = strategy.acquire()
        assertTrue(acquisition is PlaybackRouteAcquisition.Unavailable)
        assertEquals("SCO transport unavailable", (acquisition as PlaybackRouteAcquisition.Unavailable).reason)
    }

    @Test
    fun strategyReturningFailedCarriesAReason() = runTest {
        val strategy = PlaybackRouteStrategy { PlaybackRouteAcquisition.Failed("audio track construction failed") }
        val acquisition = strategy.acquire()
        assertTrue(acquisition is PlaybackRouteAcquisition.Failed)
        assertEquals("audio track construction failed", (acquisition as PlaybackRouteAcquisition.Failed).reason)
    }

    @Test
    fun acquiredRouteStartReturnsAnActivePlayback() = runTest {
        val route = FakeAcquiredRoute()
        val recording = RecordedPcm(ShortArray(10) { it.toShort() }, 16_000)

        val playback = route.start(recording)
        assertEquals(AudioRouteEndpoint.Unspecified, route.endpoint)
        assertTrue(playback is FakeActivePlayback)
    }

    @Test
    fun acquiredRouteReleaseIsExactlyOnce() = runTest {
        val route = FakeAcquiredRoute()
        assertFalse(route.released)
        assertEquals(0, route.releaseCount)

        route.release()
        assertTrue(route.released)
        assertEquals(1, route.releaseCount)

        // Second release is a no-op.
        route.release()
        assertEquals(1, route.releaseCount)
    }

    @Test
    fun activePlaybackAwaitCompletionResolvesExactlyOnceWithTerminalClassification() = runTest {
        val playback = FakeActivePlayback()
        playback.complete(PlaybackCompletion.Completed)
        assertEquals(PlaybackCompletion.Completed, playback.awaitCompletion())

        // A second await returns the same result; the deferred is not re-completed.
        assertEquals(PlaybackCompletion.Completed, playback.awaitCompletion())
    }

    @Test
    fun activePlaybackRejectPttWithToneReturnsTrueForFirstCallAndFalseAfterCompletion() = runTest {
        val playback = FakeActivePlayback()
        assertTrue(playback.rejectPttWithTone())
        // The single-slot debounce prevents a second tone from being latched.
        assertFalse(playback.rejectPttWithTone())

        playback.complete(PlaybackCompletion.Completed)
        assertFalse(playback.rejectPttWithTone())
    }

    @Test
    fun activePlaybackSkipReturnsTrueBeforeCompletionAndFalseAfter() = runTest {
        val playback = FakeActivePlayback()
        assertTrue(playback.skip())
        // A second skip is a no-op.
        assertFalse(playback.skip())

        playback.complete(PlaybackCompletion.ExplicitlySkipped)
        assertFalse(playback.skip())
    }

    @Test
    fun activePlaybackSkipCancelsALatchedTone() = runTest {
        val playback = FakeActivePlayback()
        // Latch a tone.
        assertTrue(playback.rejectPttWithTone())
        // Skip wins and cancels the latched tone.
        assertTrue(playback.skip())
        // After skip, no tone can be latched (completion is pending).
        assertFalse(playback.rejectPttWithTone())
    }

    @Test
    fun strategyCompositionCanChainReleaseAfterPlaybackTermination() = runTest {
        val innerRoute = FakeAcquiredRoute()
        val releasedFlag = CompletableDeferred<Unit>()
        val releasingRoute = ReleasingPlaybackRoute(innerRoute) { releasedFlag.complete(Unit) }
        val strategy = PlaybackRouteStrategy { PlaybackRouteAcquisition.Acquired(releasingRoute) }

        val acquired = strategy.acquire() as PlaybackRouteAcquisition.Acquired
        val playback = acquired.route.start(RecordedPcm(ShortArray(1), 16_000)) as FakeActivePlayback
        playback.complete(PlaybackCompletion.Completed)
        assertEquals(PlaybackCompletion.Completed, playback.awaitCompletion())

        acquired.route.release()
        assertTrue(innerRoute.released)
        assertTrue(releasedFlag.isCompleted)
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------


    internal class FakeAcquiredRoute : AcquiredPlaybackRoute {
        override val endpoint: AudioRouteEndpoint = AudioRouteEndpoint.Unspecified

        var released = false
            private set
        var releaseCount = 0
            private set

        override suspend fun start(recording: RecordedPcm): ActivePcmPlayback = FakeActivePlayback()

        override suspend fun release() {
            if (released) return
            released = true
            releaseCount += 1
        }
    }

    internal class FakeActivePlayback : ActivePcmPlayback {
        private val completion = CompletableDeferred<PlaybackCompletion>()
        private val toneSlot = java.util.concurrent.atomic.AtomicBoolean(false)

        fun complete(result: PlaybackCompletion) {
            completion.complete(result)
        }

        override suspend fun awaitCompletion(): PlaybackCompletion = completion.await()

        override fun rejectPttWithTone(): Boolean {
            if (!completion.isActive) return false
            return toneSlot.compareAndSet(false, true)
        }

        override fun skip(): Boolean {
            if (!completion.isActive) return false
            toneSlot.set(false) // skip wins; cancel any latched tone
            completion.complete(PlaybackCompletion.ExplicitlySkipped)
            return true
        }
    }

    /**
     * Mirrors [ModePlaybackRouteResolver.ReleasingPlaybackRoute]: delegates start to an inner
     * route and chains a release callback after the inner route is released.
     */
    internal class ReleasingPlaybackRoute(
        private val delegate: AcquiredPlaybackRoute,
        private val onRelease: () -> Unit,
    ) : AcquiredPlaybackRoute {
        override val endpoint: AudioRouteEndpoint get() = delegate.endpoint

        override suspend fun start(recording: RecordedPcm): ActivePcmPlayback = delegate.start(recording)

        override suspend fun release() {
            try {
                delegate.release()
            } finally {
                onRelease()
            }
        }
    }
}