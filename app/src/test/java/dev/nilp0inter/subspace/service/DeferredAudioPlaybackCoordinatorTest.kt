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
import kotlinx.coroutines.test.advanceUntilIdle
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

    private fun coordinator(
        scope: kotlinx.coroutines.CoroutineScope,
        selectedChannel: suspend () -> String?,
        audio: DeferredAudioPlaybackAudioPort,
        operationIsCurrent: suspend (AgentOperationContext) -> Boolean = { true },
    ) = DeferredAudioPlaybackCoordinator(
        scope = scope,
        selectedChannel = selectedChannel,
        operationIsCurrent = operationIsCurrent,
        audio = audio,
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
}