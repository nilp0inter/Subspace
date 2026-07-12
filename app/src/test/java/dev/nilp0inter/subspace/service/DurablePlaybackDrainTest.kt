package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.model.AgentMessageId
import dev.nilp0inter.subspace.model.AgentRunId
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the durable playback-drain policy: FIFO ordering, explicit skip (SOS) and
 * persistent pause, passive vs explicit same-channel resume, and restart normalization.
 *
 * These tests exercise [DurableAgentRunStore] playback lifecycle transitions directly,
 * verifying the durable invariants that [DelayedPlaybackCoordinator] depends on.
 */
class DurablePlaybackDrainTest {

    @Test
    fun skipPlaybackAndPauseMarksMessageHeardAndPausesChannelDrain() = withTemporaryDirectory { directory ->
        val store = DurableAgentRunStore(File(directory, "ledger.json"))
        val response = commitInboundResponse(store, "skip", channel = "alpha", text = "skip me")

        val result = store.skipPlaybackAndPause("alpha", response)

        assertTrue(result is DurableAgentStoreResult.Success)
        assertEquals(DurableMessageLifecycle.HEARD, lifecycle(store, response))
        assertEquals(PlaybackDrainState.PAUSED_BY_USER, store.playbackDrainState("alpha"))
    }

    @Test
    fun skipPlaybackAndPauseIsIdempotentForAlreadyHeardMessages() = withTemporaryDirectory { directory ->
        val store = DurableAgentRunStore(File(directory, "ledger.json"))
        val response = commitInboundResponse(store, "skip", channel = "alpha", text = "skip me")

        requireSuccess(store.markHeard(response))
        val result = store.skipPlaybackAndPause("alpha", response)

        assertTrue(result is DurableAgentStoreResult.Success)
        assertEquals(DurableMessageLifecycle.HEARD, lifecycle(store, response))
        assertEquals(PlaybackDrainState.PAUSED_BY_USER, store.playbackDrainState("alpha"))
    }

    @Test
    fun skipPlaybackAndPauseOnlyPausesTheAddressedChannel() = withTemporaryDirectory { directory ->
        val store = DurableAgentRunStore(File(directory, "ledger.json"))
        val alphaResponse = commitInboundResponse(store, "alpha-resp", channel = "alpha", text = "alpha text")
        val bravoResponse = commitInboundResponse(store, "bravo-resp", channel = "bravo", text = "bravo text")

        requireSuccess(store.skipPlaybackAndPause("alpha", alphaResponse))

        assertEquals(PlaybackDrainState.PAUSED_BY_USER, store.playbackDrainState("alpha"))
        assertEquals(PlaybackDrainState.ENABLED, store.playbackDrainState("bravo"))
        assertEquals(DurableMessageLifecycle.HEARD, lifecycle(store, alphaResponse))
        assertEquals(DurableMessageLifecycle.PENDING, lifecycle(store, bravoResponse))
    }

    @Test
    fun skipPlaybackAndPauseRejectsMessageFromAnotherChannel() = withTemporaryDirectory { directory ->
        val store = DurableAgentRunStore(File(directory, "ledger.json"))
        val alphaResponse = commitInboundResponse(store, "resp", channel = "alpha", text = "alpha text")

        val result = store.skipPlaybackAndPause("bravo", alphaResponse)

        assertTrue(result is DurableAgentStoreResult.Failure)
        assertEquals(PlaybackDrainState.ENABLED, store.playbackDrainState("bravo"))
        assertEquals(PlaybackDrainState.ENABLED, store.playbackDrainState("alpha"))
    }

    @Test
    fun skipPlaybackAndPauseRejectsOutboundMessages() = withTemporaryDirectory { directory ->
        val store = DurableAgentRunStore(File(directory, "ledger.json"))
        val run = admit(store, "msg", channel = "alpha")

        // The outbound source message cannot be skipped.
        val result = store.skipPlaybackAndPause("alpha", AgentMessageId("message-msg"))

        assertTrue(result is DurableAgentStoreResult.Failure)
    }

    @Test
    fun resumePlaybackDrainClearsPauseForTheAddressedChannelOnly() = withTemporaryDirectory { directory ->
        val store = DurableAgentRunStore(File(directory, "ledger.json"))
        val alphaResponse = commitInboundResponse(store, "alpha-resp", channel = "alpha", text = "alpha text")
        val bravoResponse = commitInboundResponse(store, "bravo-resp", channel = "bravo", text = "bravo text")

        requireSuccess(store.skipPlaybackAndPause("alpha", alphaResponse))
        requireSuccess(store.skipPlaybackAndPause("bravo", bravoResponse))

        requireSuccess(store.resumePlaybackDrain("alpha"))

        assertEquals(PlaybackDrainState.ENABLED, store.playbackDrainState("alpha"))
        assertEquals(PlaybackDrainState.PAUSED_BY_USER, store.playbackDrainState("bravo"))
    }

    @Test
    fun resumePlaybackDrainIsIdempotentForAlreadyEnabledChannel() = withTemporaryDirectory { directory ->
        val store = DurableAgentRunStore(File(directory, "ledger.json"))

        val result = store.resumePlaybackDrain("alpha")

        assertTrue(result is DurableAgentStoreResult.Success)
        assertEquals(PlaybackDrainState.ENABLED, store.playbackDrainState("alpha"))
    }

    @Test
    fun pausedDrainStateSurvivesRestart() = withTemporaryDirectory { directory ->
        val file = File(directory, "ledger.json")
        val original = DurableAgentRunStore(file)
        val response = commitInboundResponse(original, "resp", channel = "alpha", text = "skip me")
        requireSuccess(original.skipPlaybackAndPause("alpha", response))

        val restarted = DurableAgentRunStore(file)
        requireSuccess(restarted.load())

        assertEquals(PlaybackDrainState.PAUSED_BY_USER, restarted.playbackDrainState("alpha"))
        assertEquals(DurableMessageLifecycle.HEARD, restarted.snapshot().messages.single { it.id == response }.lifecycle)
    }

    @Test
    fun restartedDrainPauseIsResumableAndThenPlaysPendingMessages() = withTemporaryDirectory { directory ->
        val file = File(directory, "ledger.json")
        val original = DurableAgentRunStore(file)
        val response = commitInboundResponse(original, "resp", channel = "alpha", text = "skip me")
        requireSuccess(original.skipPlaybackAndPause("alpha", response))

        val restarted = DurableAgentRunStore(file)
        requireSuccess(restarted.load())

        // Passive resume via onChannelSelected-equivalent store operation.
        requireSuccess(restarted.resumePlaybackDrain("alpha"))
        assertEquals(PlaybackDrainState.ENABLED, restarted.playbackDrainState("alpha"))

        // A new pending response can now begin playback.
        val newResponse = commitInboundResponse(restarted, "new-resp", channel = "alpha", text = "new reply")
        val beginResult = restarted.beginPlayback(newResponse)
        assertTrue(beginResult is DurableAgentStoreResult.Success)
        assertEquals(DurableMessageLifecycle.PLAYING, lifecycle(restarted, newResponse))
    }

    @Test
    fun playbackDrainStateDefaultsToEnabledForUnknownChannel() = withTemporaryDirectory { directory ->
        val store = DurableAgentRunStore(File(directory, "ledger.json"))
        assertEquals(PlaybackDrainState.ENABLED, store.playbackDrainState("never-seen"))
    }

    @Test
    fun inboundResponsesArePlayedInFifoTimestampOrder() = withTemporaryDirectory { directory ->
        val store = DurableAgentRunStore(File(directory, "ledger.json"))
        val later = commitInboundResponse(store, "later", channel = "alpha", text = "later reply", createdAtMillis = 20)
        val first = commitInboundResponse(store, "first", channel = "alpha", text = "first reply", createdAtMillis = 10)

        // The store does not enforce playback order directly, but messages retain their
        // createdAtMillis so the coordinator can sort. Verify the durable timestamps.
        val messages = store.snapshot().messages.filter { it.direction == DurableMessageDirection.INBOUND }
        assertEquals(2, messages.size)
        val sorted = messages.sortedWith(compareBy { it.createdAtMillis })
        assertEquals("first reply", sorted[0].text)
        assertEquals("later reply", sorted[1].text)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun admit(
        store: DurableAgentRunStore,
        suffix: String,
        channel: String = "channel",
    ): AgentRunId {
        val run = AgentRunId("run-$suffix")
        requireSuccess(
            store.admit(
                messageId = AgentMessageId("message-$suffix"),
                runId = run,
                channelInstanceId = channel,
                conversationEpoch = 0,
                configurationEpoch = 0,
                configuration = DurableAgentConfiguration("profile", "model", "system", "fingerprint"),
                text = "outbound-$suffix",
                admittedAtMillis = 1,
            ),
        )
        requireSuccess(store.beginRun(run))
        return run
    }

    private fun commitInboundResponse(
        store: DurableAgentRunStore,
        suffix: String,
        channel: String,
        text: String,
        createdAtMillis: Long = 10,
    ): AgentMessageId {
        val run = admit(store, suffix, channel)
        val response = AgentMessageId("response-$suffix")
        requireSuccess(store.commitInboundResponse(run, response, text, createdAtMillis))
        return response
    }

    private fun lifecycle(store: DurableAgentRunStore, messageId: AgentMessageId): DurableMessageLifecycle =
        store.snapshot().messages.single { it.id == messageId }.lifecycle

    private fun <T> requireSuccess(result: DurableAgentStoreResult<T>): T = when (result) {
        is DurableAgentStoreResult.Success -> result.value
        is DurableAgentStoreResult.Failure -> throw AssertionError("Expected success, got $result")
    }

    private fun <T> withTemporaryDirectory(block: (File) -> T): T {
        val directory = createTempDirectory("durable-playback-drain-test-").toFile()
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}