package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.channel.capability.AgentOperationContext
import dev.nilp0inter.subspace.channel.capability.AudioOperationArtifact
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity
import dev.nilp0inter.subspace.channel.capability.OpaqueAudioOperation
import dev.nilp0inter.subspace.channel.capability.RuntimeGeneration
import dev.nilp0inter.subspace.model.AgentOperationId
import dev.nilp0inter.subspace.model.AgentRunId
import dev.nilp0inter.subspace.model.DelayedPlaybackFailureReason
import dev.nilp0inter.subspace.model.DelayedPlaybackOutcome
import java.util.ArrayDeque
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeferredAudioPlaybackCoordinatorTest {
    @Test
    fun staleOperationIsNotEnqueued() = runTest {
        val audio = RecordingAudio(ArrayDeque())
        val coordinator = coordinator(this, { "alpha" }, audio, operationIsCurrent = { false })

        val outcome = coordinator.scheduleAudio(context("alpha"), operation("stale"))

        assertEquals(DelayedPlaybackOutcome.Stale, outcome)
        advanceUntilIdle()
        assertTrue(audio.requests.isEmpty())
    }

    @Test
    fun unselectedChannelAudioRemainsPendingUntilSelected() = runTest {
        val audio = RecordingAudio(ArrayDeque())
        var selected = "bravo"
        val coordinator = coordinator(this, { selected }, audio)

        val outcome = coordinator.scheduleAudio(context("alpha"), operation("pending"))
        advanceUntilIdle()

        assertTrue(outcome is DelayedPlaybackOutcome.Pending)
        assertTrue(audio.requests.isEmpty())

        selected = "alpha"
        coordinator.onChannelSelected("alpha")
        advanceUntilIdle()

        assertEquals(1, audio.requests.size)
        assertEquals("alpha", audio.requests.single().channelInstanceId)
    }

    @Test
    fun selectedChannelAudioIsDeliveredOnceAndRemovedFromQueue() = runTest {
        val artifact = operation("delivered")
        val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed)))
        val coordinator = coordinator(this, { "alpha" }, audio)

        val outcome = coordinator.scheduleAudio(context("alpha"), artifact)
        advanceUntilIdle()

        assertTrue(outcome is DelayedPlaybackOutcome.Pending)
        assertEquals(1, audio.requests.size)
        assertSame(artifact, audio.requests.single().audio)

        coordinator.onAudioAvailable()
        advanceUntilIdle()

        assertEquals(1, audio.requests.size)
    }

    @Test
    fun busyAudioRetriesTheSameArtifactWhenAdmissionReopens() = runTest {
        val artifact = operation("retry")
        val audio = RecordingAudio(
            ArrayDeque(listOf(DelayedPlaybackAudioResult.Busy, DelayedPlaybackAudioResult.Completed)),
        )
        val coordinator = coordinator(this, { "alpha" }, audio)

        coordinator.scheduleAudio(context("alpha"), artifact)
        advanceUntilIdle()

        assertEquals(1, audio.requests.size)

        coordinator.onAudioAvailable()
        advanceUntilIdle()

        assertEquals(2, audio.requests.size)
        assertSame(artifact, audio.requests[0].audio)
        assertSame(artifact, audio.requests[1].audio)
    }

    @Test
    fun selectionChangeDuringDeliveryDiscardsTheEntryWithoutPlayback() = runTest {
        val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed)))
        var firstCall = true
        val coordinator = coordinator(
            this,
            selectedChannel = {
                if (firstCall) {
                    firstCall = false
                    "alpha"
                } else {
                    "bravo"
                }
            },
            audio = audio,
        )

        coordinator.scheduleAudio(context("alpha"), operation("discarded"))
        advanceUntilIdle()

        assertTrue(audio.requests.isEmpty())
    }

    @Test
    fun explicitlySkippedAudioIsRemovedFromTheQueue() = runTest {
        val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.ExplicitlySkipped)))
        val coordinator = coordinator(this, { "alpha" }, audio)

        coordinator.scheduleAudio(context("alpha"), operation("skipped"))
        advanceUntilIdle()

        assertEquals(1, audio.requests.size)

        coordinator.onAudioAvailable()
        advanceUntilIdle()

        assertEquals(1, audio.requests.size)
    }

    @Test
    fun interruptedCancelledAndFailedAudioStaysPendingForRetry() = runTest {
        data class FailureCase(
            val name: String,
            val result: DelayedPlaybackAudioResult,
        )
        val cases = listOf(
            FailureCase("interrupted", DelayedPlaybackAudioResult.Interrupted),
            FailureCase("cancelled", DelayedPlaybackAudioResult.Cancelled),
            FailureCase("failed", DelayedPlaybackAudioResult.Failed(DelayedPlaybackFailureReason.PLAYBACK_FAILED)),
        )

        cases.forEach { failure ->
            val audio = RecordingAudio(ArrayDeque(listOf(failure.result, DelayedPlaybackAudioResult.Completed)))
            val coordinator = coordinator(this, { "alpha" }, audio)

            coordinator.scheduleAudio(context("alpha"), operation(failure.name))
            advanceUntilIdle()

            assertEquals(failure.name, 1, audio.requests.size)

            coordinator.onAudioAvailable()
            advanceUntilIdle()

            assertEquals(failure.name, 2, audio.requests.size)
        }
    }

    @Test
    fun thrownAudioBoundaryKeepsEntryPendingAndRetriesOnNextPump() = runTest {
        val audio = ThrowingThenCompletingAudio()
        val coordinator = coordinator(this, { "alpha" }, audio)

        coordinator.scheduleAudio(context("alpha"), operation("exception"))
        advanceUntilIdle()

        assertEquals(1, audio.requests.size)

        coordinator.onAudioAvailable()
        advanceUntilIdle()

        assertEquals(2, audio.requests.size)
    }

    @Test
    fun multiplePendingEntriesAreDeliveredInFifoOrderForTheSelectedChannel() = runTest {
        val first = operation("first")
        val second = operation("second")
        val audio = RecordingAudio(
            ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed, DelayedPlaybackAudioResult.Completed)),
        )
        val coordinator = coordinator(this, { "alpha" }, audio)

        coordinator.scheduleAudio(context("alpha"), first)
        coordinator.scheduleAudio(context("alpha"), second)
        advanceUntilIdle()

        assertEquals(2, audio.requests.size)
        assertSame(first, audio.requests[0].audio)
        assertSame(second, audio.requests[1].audio)
    }

    @Test
    fun closeClearsPendingAndCancelsDelivery() = runTest {
        val audio = RecordingAudio(ArrayDeque())
        val coordinator = coordinator(this, { "bravo" }, audio)

        coordinator.scheduleAudio(context("alpha"), operation("pending"))
        advanceUntilIdle()
        assertTrue(audio.requests.isEmpty())

        coordinator.close()
        coordinator.onAudioAvailable()
        advanceUntilIdle()

        assertTrue(audio.requests.isEmpty())
    }

    @Test
    fun delayedAudioIsNotPlayedBeforeEligibilityDelayElapses() = runTest {
        val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed)))
        val virtualClock = VirtualClock(this)
        val coordinator = coordinator(this, { "alpha" }, audio, nowMillis = virtualClock::now)

        val outcome = coordinator.scheduleAudio(context("alpha"), operation("delayed"), eligibilityDelayMillis = 5_000L)
        runCurrent()

        assertTrue(outcome is DelayedPlaybackOutcome.Pending)
        assertTrue("audio must not play before eligibility delay", audio.requests.isEmpty())

        advanceTimeBy(4_999L)
        runCurrent()
        assertTrue("audio must still not play one millisecond before eligibility", audio.requests.isEmpty())
    }

    @Test
    fun delayedAudioWakesAtEligibilityAndPlays() = runTest {
        val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed)))
        val virtualClock = VirtualClock(this)
        val coordinator = coordinator(this, { "alpha" }, audio, nowMillis = virtualClock::now)

        coordinator.scheduleAudio(context("alpha"), operation("delayed"), eligibilityDelayMillis = 5_000L)
        runCurrent()
        assertTrue(audio.requests.isEmpty())

        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals("audio must play once eligibility delay elapses", 1, audio.requests.size)
        assertEquals("alpha", audio.requests.single().channelInstanceId)
    }

    @Test
    fun delayedSameChannelEntriesDoNotOvertakeBeforeEligibility() = runTest {
        val first = operation("delayed-first")
        val second = operation("immediate-second")
        val audio = RecordingAudio(
            ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed, DelayedPlaybackAudioResult.Completed)),
        )
        val virtualClock = VirtualClock(this)
        val coordinator = coordinator(this, { "alpha" }, audio, nowMillis = virtualClock::now)

        coordinator.scheduleAudio(context("alpha"), first, eligibilityDelayMillis = 5_000L)
        coordinator.scheduleAudio(context("alpha"), second, eligibilityDelayMillis = 0L)
        runCurrent()

        assertTrue("second entry must not overtake delayed first entry", audio.requests.isEmpty())

        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(2, audio.requests.size)
        assertSame("FIFO order preserved: delayed first plays before immediate second", first, audio.requests[0].audio)
        assertSame(second, audio.requests[1].audio)
    }

    @Test
    fun delayedAudioOnInactiveChannelIsRetainedAndDeliveredAfterSelection() = runTest {
        val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed)))
        val virtualClock = VirtualClock(this)
        var selected = "bravo"
        val coordinator = coordinator(this, { selected }, audio, nowMillis = virtualClock::now)

        val outcome = coordinator.scheduleAudio(context("alpha"), operation("retained"), eligibilityDelayMillis = 5_000L)
        runCurrent()
        assertTrue(outcome is DelayedPlaybackOutcome.Pending)
        assertTrue("must not play on unselected channel", audio.requests.isEmpty())

        advanceTimeBy(5_000L)
        runCurrent()
        assertTrue("must retain even after eligibility while channel unselected", audio.requests.isEmpty())

        selected = "alpha"
        coordinator.onChannelSelected("alpha")
        advanceUntilIdle()

        assertEquals(1, audio.requests.size)
        assertEquals("alpha", audio.requests.single().channelInstanceId)
    }

    @Test
    fun closeCancelsDelayedWakeAndPreventsSubsequentPlayback() = runTest {
        val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed)))
        val virtualClock = VirtualClock(this)
        val coordinator = coordinator(this, { "alpha" }, audio, nowMillis = virtualClock::now)

        coordinator.scheduleAudio(context("alpha"), operation("cancelled-wake"), eligibilityDelayMillis = 5_000L)
        runCurrent()
        assertTrue(audio.requests.isEmpty())

        coordinator.close()
        advanceTimeBy(5_000L)
        runCurrent()
        coordinator.onAudioAvailable()
        advanceUntilIdle()

        assertTrue("cancelled wake must not trigger playback after close", audio.requests.isEmpty())
    }

    @Test
    fun delayedPendingCountsAppearOnlyAtEligibilityOnInactiveChannel() = runTest {
        val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed)))
        val virtualClock = VirtualClock(this)
        val coordinator = coordinator(this, { "bravo" }, audio, nowMillis = virtualClock::now)

        coordinator.scheduleAudio(context("alpha"), operation("delayed"), eligibilityDelayMillis = 5_000L)
        runCurrent()

        assertEquals("delayed Echo must not count before eligibility", emptyMap<String, Int>(), coordinator.pendingCounts.value)

        advanceTimeBy(4_999L)
        runCurrent()
        assertEquals("delayed Echo must still not count one millisecond before eligibility", emptyMap<String, Int>(), coordinator.pendingCounts.value)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals("delayed Echo must count exactly at eligibility while its channel is inactive", mapOf("alpha" to 1), coordinator.pendingCounts.value)
        assertTrue("eligible inactive Echo must remain queued", audio.requests.isEmpty())
    }

    @Test
    fun pendingCountsRetainsInactiveChannelAcrossDelayAndSelection() = runTest {
        val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed)))
        val virtualClock = VirtualClock(this)
        var selected = "bravo"
        val coordinator = coordinator(this, { selected }, audio, nowMillis = virtualClock::now)

        coordinator.scheduleAudio(context("alpha"), operation("retained"), eligibilityDelayMillis = 5_000L)
        runCurrent()
        assertEquals("ineligible inactive entry must not count", emptyMap<String, Int>(), coordinator.pendingCounts.value)

        advanceTimeBy(4_999L)
        runCurrent()
        assertEquals("ineligible inactive entry must still not count one millisecond before eligibility", emptyMap<String, Int>(), coordinator.pendingCounts.value)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals("eligible inactive entry must count", mapOf("alpha" to 1), coordinator.pendingCounts.value)
        assertTrue("must not play on unselected channel", audio.requests.isEmpty())

        selected = "alpha"
        coordinator.onChannelSelected("alpha")
        advanceUntilIdle()

        assertEquals("count cleared after delivery on selection", emptyMap<String, Int>(), coordinator.pendingCounts.value)
    }

    @Test
    fun pendingCountsDecrementsOnCompletion() = runTest {
        val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed)))
        val coordinator = coordinator(this, { "alpha" }, audio)

        coordinator.scheduleAudio(context("alpha"), operation("done"), eligibilityDelayMillis = 0L)
        assertEquals("zero-delay entry counts immediately before its pump runs", mapOf("alpha" to 1), coordinator.pendingCounts.value)

        advanceUntilIdle()

        assertEquals("completed entry removed from counts", emptyMap<String, Int>(), coordinator.pendingCounts.value)
    }

    @Test
    fun pendingCountsDecrementsOnExplicitSkip() = runTest {
        val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.ExplicitlySkipped)))
        val coordinator = coordinator(this, { "alpha" }, audio)

        coordinator.scheduleAudio(context("alpha"), operation("skipped"))
        assertEquals("count present before delivery", mapOf("alpha" to 1), coordinator.pendingCounts.value)

        coordinator.onAudioAvailable()
        advanceUntilIdle()

        assertEquals("explicitly skipped entry removed from counts", emptyMap<String, Int>(), coordinator.pendingCounts.value)
    }

    @Test
    fun pendingCountsRetainsAcrossRetryableFailures() = runTest {
        data class FailureCase(val name: String, val result: DelayedPlaybackAudioResult)
        val cases = listOf(
            FailureCase("busy", DelayedPlaybackAudioResult.Busy),
            FailureCase("interrupted", DelayedPlaybackAudioResult.Interrupted),
            FailureCase("cancelled", DelayedPlaybackAudioResult.Cancelled),
            FailureCase("failed", DelayedPlaybackAudioResult.Failed(DelayedPlaybackFailureReason.PLAYBACK_FAILED)),
        )

        cases.forEach { failure ->
            val audio = RecordingAudio(ArrayDeque(listOf(failure.result, DelayedPlaybackAudioResult.Completed)))
            val coordinator = coordinator(this, { "alpha" }, audio)

            coordinator.scheduleAudio(context("alpha"), operation(failure.name))
            runCurrent()
            assertEquals("${failure.name}: count present after schedule", mapOf("alpha" to 1), coordinator.pendingCounts.value)

            advanceUntilIdle()

            assertEquals("${failure.name}: count retained after retryable failure", mapOf("alpha" to 1), coordinator.pendingCounts.value)
        }
    }

    @Test
    fun pendingCountsClearsOnClose() = runTest {
        val audio = RecordingAudio(ArrayDeque())
        val coordinator = coordinator(this, { "bravo" }, audio)

        coordinator.scheduleAudio(context("alpha"), operation("pending"))
        runCurrent()
        assertEquals(mapOf("alpha" to 1), coordinator.pendingCounts.value)

        coordinator.close()

        assertEquals("close clears all pending counts", emptyMap<String, Int>(), coordinator.pendingCounts.value)
    }

    @Test
    fun pendingCountsAccumulateAcrossChannelsAndOmitZeroEntries() = runTest {
        val audio = RecordingAudio(
            ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed, DelayedPlaybackAudioResult.Completed)),
        )
        val coordinator = coordinator(this, { "alpha" }, audio)

        coordinator.scheduleAudio(context("alpha"), operation("alpha-a"))
        coordinator.scheduleAudio(context("bravo"), operation("bravo-a"))
        assertEquals("additive counting across channels", mapOf("alpha" to 1, "bravo" to 1), coordinator.pendingCounts.value)

        advanceUntilIdle()

        assertEquals("completed channel omitted, unselected channel retained", mapOf("bravo" to 1), coordinator.pendingCounts.value)
    }

    private fun coordinator(
        scope: kotlinx.coroutines.CoroutineScope,
        selectedChannel: suspend () -> String?,
        audio: DeferredAudioPlaybackAudioPort,
        operationIsCurrent: suspend (AgentOperationContext) -> Boolean = { true },
        nowMillis: () -> Long = { System.currentTimeMillis() },
    ) = DeferredAudioPlaybackCoordinator(
        scope = scope,
        selectedChannel = selectedChannel,
        operationIsCurrent = operationIsCurrent,
        audio = audio,
        nowMillis = nowMillis,
    )

    private fun context(channelInstanceId: String): AgentOperationContext = AgentOperationContext(
        scope = CapabilityScopeIdentity(channelInstanceId, RuntimeGeneration(0)),
        runId = AgentRunId("run-$channelInstanceId"),
        operationId = AgentOperationId("operation-$channelInstanceId"),
    )

    private fun operation(operationId: String): OpaqueAudioOperation =
        AudioOperationArtifact(RecordedPcm(shortArrayOf(1), 16_000), operationId = operationId)

    private data class PlaybackRequest(
        val channelInstanceId: String,
        val audio: OpaqueAudioOperation,
    )

    private class RecordingAudio(
        private val outcomes: ArrayDeque<DelayedPlaybackAudioResult>,
    ) : DeferredAudioPlaybackAudioPort {
        val requests = mutableListOf<PlaybackRequest>()

        override suspend fun playOperationIfAdmitted(
            channelInstanceId: String,
            audio: OpaqueAudioOperation,
        ): DelayedPlaybackAudioResult {
            requests += PlaybackRequest(channelInstanceId, audio)
            return outcomes.removeFirst()
        }
    }

    private class ThrowingThenCompletingAudio : DeferredAudioPlaybackAudioPort {
        val requests = mutableListOf<PlaybackRequest>()
        private var firstAttempt = true

        override suspend fun playOperationIfAdmitted(
            channelInstanceId: String,
            audio: OpaqueAudioOperation,
        ): DelayedPlaybackAudioResult {
            requests += PlaybackRequest(channelInstanceId, audio)
            if (firstAttempt) {
                firstAttempt = false
                throw IllegalStateException("audio acknowledgement lost")
            }
            return DelayedPlaybackAudioResult.Completed
        }
    }
    private class VirtualClock(private val scope: TestScope) {
        fun now(): Long = scope.testScheduler.currentTime
    }

}