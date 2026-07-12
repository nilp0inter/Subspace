package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.model.AgentMessageId
import dev.nilp0inter.subspace.model.AgentOperationId
import dev.nilp0inter.subspace.model.AgentRunId
import dev.nilp0inter.subspace.model.AgentToolCallId
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableAgentRunStoreTest {
    @Test
    fun admissionStorageFailureDoesNotExposeHalfCommittedMessageOrRun() = withTemporaryDirectory { directory ->
        val nonDirectory = File(directory, "ledger-parent").apply { writeText("not a directory") }
        val store = DurableAgentRunStore(File(nonDirectory, "agent-runs.json"))

        val result = store.admit(
            messageId = AgentMessageId("message-1"),
            runId = AgentRunId("run-1"),
            channelInstanceId = "channel-1",
            conversationEpoch = 0,
            configurationEpoch = 0,
            configuration = configuration(),
            text = "outbound text",
            admittedAtMillis = 1,
        )

        assertTrue(result is DurableAgentStoreResult.Failure)
        assertTrue((result as DurableAgentStoreResult.Failure).error is DurableAgentStoreFailure.Storage)
        assertTrue(store.snapshot().messages.isEmpty())
        assertTrue(store.snapshot().runs.isEmpty())
    }

    @Test
    fun restartRecoveryClassifiesEveryEffectBoundaryWithoutReplayingAmbiguousWork() {
        data class Boundary(
            val name: String,
            val prepare: (DurableAgentRunStore, AgentRunId) -> Unit,
            val expectedState: DurableRunState,
            val resumable: Boolean,
        )
        val boundaries = listOf(
            Boundary("queued admission", { _, _ -> }, DurableRunState.QUEUED, true),
            Boundary(
                "active run before a request envelope",
                { store, run -> requireSuccess(store.beginRun(run)) },
                DurableRunState.INDETERMINATE,
                false,
            ),
            Boundary(
                "request envelope before remote submission",
                { store, run ->
                    requireSuccess(store.beginRun(run))
                    requireSuccess(store.recordOutboundEnvelope(run, AgentOperationId("request-envelope"), DurableContinuation.INITIAL_REQUEST))
                },
                DurableRunState.QUEUED,
                true,
            ),
            Boundary(
                "remote submission started",
                { store, run ->
                    requireSuccess(store.beginRun(run))
                    requireSuccess(store.recordOutboundEnvelope(run, AgentOperationId("submitted-request"), DurableContinuation.INITIAL_REQUEST))
                    requireSuccess(store.markRemoteSubmissionStarted(run))
                },
                DurableRunState.INDETERMINATE,
                false,
            ),
            Boundary(
                "tool reserved before its effect",
                { store, run ->
                    requireSuccess(store.beginRun(run))
                    requireSuccess(store.reserveToolCall(run, AgentToolCallId("tool-reserved"), "keyboard_type_text", "hash", AgentOperationId("tool-operation")))
                },
                DurableRunState.INDETERMINATE,
                false,
            ),
            Boundary(
                "tool effect started",
                { store, run ->
                    requireSuccess(store.beginRun(run))
                    requireSuccess(store.reserveToolCall(run, AgentToolCallId("tool-started"), "keyboard_type_text", "hash", AgentOperationId("tool-operation")))
                    requireSuccess(store.markToolEffectStarted(run, AgentToolCallId("tool-started")))
                },
                DurableRunState.INDETERMINATE,
                false,
            ),
            Boundary(
                "tool result before continuation envelope",
                { store, run ->
                    requireSuccess(store.beginRun(run))
                    requireSuccess(store.reserveToolCall(run, AgentToolCallId("tool-result-only"), "keyboard_type_text", "hash", AgentOperationId("tool-operation")))
                    requireSuccess(store.markToolEffectStarted(run, AgentToolCallId("tool-result-only")))
                    requireSuccess(store.recordToolResult(run, AgentToolCallId("tool-result-only"), DurableToolResult(DurableToolOutcome.DELIVERED, "delivered")))
                },
                DurableRunState.INDETERMINATE,
                false,
            ),
            Boundary(
                "tool result committed and continuation envelope persisted",
                { store, run ->
                    requireSuccess(store.beginRun(run))
                    requireSuccess(store.reserveToolCall(run, AgentToolCallId("tool-result"), "keyboard_type_text", "hash", AgentOperationId("tool-operation")))
                    requireSuccess(store.markToolEffectStarted(run, AgentToolCallId("tool-result")))
                    requireSuccess(store.recordToolResult(run, AgentToolCallId("tool-result"), DurableToolResult(DurableToolOutcome.DELIVERED, "delivered")))
                    requireSuccess(store.recordOutboundEnvelope(run, AgentOperationId("tool-continuation"), DurableContinuation.TOOL_RESULT_CONTINUATION))
                },
                DurableRunState.QUEUED,
                true,
            ),
        )

        boundaries.forEachIndexed { index, boundary ->
            withTemporaryDirectory { directory ->
                val file = File(directory, "agent-runs.json")
                val original = DurableAgentRunStore(file)
                val run = admit(original, "boundary-$index")
                boundary.prepare(original, run)

                val restarted = DurableAgentRunStore(file)
                requireSuccess(restarted.load())
                val recovery = requireSuccess(restarted.reconcileAfterRestart())
                val recoveredRun = restarted.snapshot().runs.single()

                assertEquals(boundary.expectedState, recoveredRun.state)
                assertEquals(boundary.resumable, run in recovery.resumableRunIds)
                if (!boundary.resumable) assertFalse(run in recovery.resumableRunIds)
            }
        }
    }

    @Test
    fun FIFOIsEnforcedPerChannelWhileAnotherChannelCanProgress() = withTemporaryDirectory { directory ->
        val store = DurableAgentRunStore(File(directory, "agent-runs.json"))
        val first = admit(store, "first", channel = "channel-a")
        val second = admit(store, "second", channel = "channel-a")
        val otherChannel = admit(store, "other", channel = "channel-b")

        val nonHead = store.beginRun(second)
        val otherBegun = requireSuccess(store.beginRun(otherChannel))
        requireSuccess(store.beginRun(first))
        requireSuccess(store.markRunTerminal(first, DurableRunTerminal(DurableRunState.CANCELLED, DurableRunTerminalReason.CANCELLED)))
        val secondBegun = requireSuccess(store.beginRun(second))

        assertTrue(nonHead is DurableAgentStoreResult.Failure)
        assertTrue((nonHead as DurableAgentStoreResult.Failure).error is DurableAgentStoreFailure.NotQueueHead)
        assertEquals(DurableRunState.ACTIVE, otherBegun.state)
        assertEquals(DurableRunState.ACTIVE, secondBegun.state)
        assertEquals(listOf(0L, 1L), store.snapshot().runs.filter { it.channelInstanceId == "channel-a" }.map { it.queueSequence })
    }

    @Test
    fun duplicateAdmissionIsIdempotentButIdentityReuseForDifferentWorkIsRejected() = withTemporaryDirectory { directory ->
        val store = DurableAgentRunStore(File(directory, "agent-runs.json"))
        val message = AgentMessageId("message-duplicate")
        val run = AgentRunId("run-duplicate")
        val first = store.admit(message, run, "channel", 0, 0, configuration(), "one", 1)
        val duplicate = store.admit(message, run, "channel", 0, 0, configuration(), "different caller text", 2)
        val conflict = store.admit(message, AgentRunId("run-conflict"), "channel", 0, 0, configuration(), "other", 3)

        assertTrue(first is DurableAgentStoreResult.Success && first.value is DurableAdmission.Accepted)
        assertTrue(duplicate is DurableAgentStoreResult.Success && duplicate.value is DurableAdmission.Duplicate)
        assertTrue(conflict is DurableAgentStoreResult.Failure)
        assertTrue((conflict as DurableAgentStoreResult.Failure).error is DurableAgentStoreFailure.Conflict)
        assertEquals(1, store.snapshot().messages.size)
        assertEquals(1, store.snapshot().runs.size)
    }

    @Test
    fun interruptedNativeToolEffectBecomesIndeterminateAndCannotBeReplayed() = withTemporaryDirectory { directory ->
        val file = File(directory, "agent-runs.json")
        val original = DurableAgentRunStore(file)
        val run = admit(original, "tool-crash")
        val call = AgentToolCallId("tool-crash-call")
        requireSuccess(original.beginRun(run))
        requireSuccess(original.reserveToolCall(run, call, "keyboard_type_text", "arguments-hash", AgentOperationId("tool-effect")))
        requireSuccess(original.markToolEffectStarted(run, call))

        val restarted = DurableAgentRunStore(file)
        requireSuccess(restarted.load())
        val plan = requireSuccess(restarted.reconcileAfterRestart())
        val snapshot = restarted.snapshot()
        val retry = restarted.markToolEffectStarted(run, call)

        assertTrue(plan.resumableRunIds.isEmpty())
        assertEquals(DurableRunState.INDETERMINATE, snapshot.runs.single().state)
        assertEquals(DurableRunTerminalReason.AMBIGUOUS_TOOL_EFFECT, snapshot.runs.single().terminalReason)
        assertEquals(DurableToolCallState.INDETERMINATE, snapshot.toolCalls.single().state)
        assertTrue(retry is DurableAgentStoreResult.Failure)
    }

    @Test
    fun conversationResetCancelsSafeQueuedWorkWithoutChangingTerminalPendingResponses() = withTemporaryDirectory { directory ->
        val store = DurableAgentRunStore(File(directory, "agent-runs.json"))
        val channel = "reset-channel"
        val terminalRun = admit(store, "terminal-before-reset", channel)
        val terminalResponse = AgentMessageId("terminal-response-before-reset")
        requireSuccess(store.beginRun(terminalRun))
        requireSuccess(store.commitInboundResponse(terminalRun, terminalResponse, "already terminal", 2))
        val queuedRun = admit(store, "queued-before-reset", channel)

        val reset = requireSuccess(store.resetConversationEpoch(channel))
        val snapshot = store.snapshot()

        assertEquals(listOf(queuedRun), reset.cancelledRunIds)
        assertTrue(reset.indeterminateRunIds.isEmpty())
        assertEquals(reset.conversationEpoch, snapshot.channelEpochs[channel])
        assertEquals(DurableRunState.CANCELLED, snapshot.runs.single { it.id == queuedRun }.state)
        assertEquals(DurableMessageLifecycle.CANCELLED, snapshot.messages.single { it.runId == queuedRun }.lifecycle)
        assertEquals(DurableMessageLifecycle.PENDING, snapshot.messages.single { it.id == terminalResponse }.lifecycle)
    }

    @Test
    fun conversationResetMarksStartedRemoteOrNativeEffectsIndeterminateInsteadOfCancellingThem() = withTemporaryDirectory { directory ->
        val store = DurableAgentRunStore(File(directory, "agent-runs.json"))
        val remoteRun = admit(store, "reset-remote", "remote-channel")
        requireSuccess(store.beginRun(remoteRun))
        requireSuccess(store.recordOutboundEnvelope(remoteRun, AgentOperationId("reset-request"), DurableContinuation.INITIAL_REQUEST))
        requireSuccess(store.markRemoteSubmissionStarted(remoteRun))
        val remoteReset = requireSuccess(store.resetConversationEpoch("remote-channel"))

        val toolRun = admit(store, "reset-tool", "tool-channel")
        val toolCall = AgentToolCallId("reset-tool-call")
        requireSuccess(store.beginRun(toolRun))
        requireSuccess(store.reserveToolCall(toolRun, toolCall, "keyboard_type_text", "hash", AgentOperationId("reset-tool-operation")))
        requireSuccess(store.markToolEffectStarted(toolRun, toolCall))
        val toolReset = requireSuccess(store.resetConversationEpoch("tool-channel"))
        val snapshot = store.snapshot()

        assertEquals(listOf(remoteRun), remoteReset.indeterminateRunIds)
        assertEquals(DurableRunTerminalReason.AMBIGUOUS_REMOTE_SUBMISSION, snapshot.runs.single { it.id == remoteRun }.terminalReason)
        assertEquals(DurableMessageLifecycle.FAILED, snapshot.messages.single { it.runId == remoteRun }.lifecycle)
        assertEquals(listOf(toolRun), toolReset.indeterminateRunIds)
        assertEquals(DurableRunTerminalReason.AMBIGUOUS_TOOL_EFFECT, snapshot.runs.single { it.id == toolRun }.terminalReason)
        assertEquals(DurableMessageLifecycle.FAILED, snapshot.messages.single { it.runId == toolRun }.lifecycle)
    }

    @Test
    fun acknowledgedRemoteCompletionClosesAmbiguityButDoesNotMakeAnUnrecordedLocalStageResumable() = withTemporaryDirectory { directory ->
        val file = File(directory, "agent-runs.json")
        val original = DurableAgentRunStore(file)
        val run = admit(original, "remote-completion")
        requireSuccess(original.beginRun(run))
        requireSuccess(original.recordOutboundEnvelope(run, AgentOperationId("initial-request"), DurableContinuation.INITIAL_REQUEST))
        requireSuccess(original.markRemoteSubmissionStarted(run))
        requireSuccess(original.acknowledgeRemoteCompletion(run))

        val restarted = DurableAgentRunStore(file)
        requireSuccess(restarted.load())
        val persisted = restarted.snapshot().runs.single()
        requireSuccess(restarted.reconcileAfterRestart())
        val recovered = restarted.snapshot().runs.single()

        assertEquals(DurableRemoteSubmission.NONE, persisted.recovery.remoteSubmission)
        assertEquals(DurableContinuation.INITIAL_REQUEST, persisted.recovery.continuation)
        assertEquals(DurableRunState.INDETERMINATE, recovered.state)
        assertEquals(DurableRunTerminalReason.UNRECOVERABLE_INTERRUPTION, recovered.terminalReason)
    }

    @Test
    fun interruptedPlaybackReturnsPendingButHeardRemainsDurablyMonotonic() = withTemporaryDirectory { directory ->
        val file = File(directory, "agent-runs.json")
        val original = DurableAgentRunStore(file)
        val run = admit(original, "playback")
        val response = AgentMessageId("response-playback")
        requireSuccess(original.beginRun(run))
        requireSuccess(original.commitInboundResponse(run, response, "assistant response", 2))
        requireSuccess(original.beginPlayback(response))

        val afterPlaybackCrash = DurableAgentRunStore(file)
        requireSuccess(afterPlaybackCrash.load())
        requireSuccess(afterPlaybackCrash.reconcileAfterRestart())
        assertEquals(DurableMessageLifecycle.PENDING, afterPlaybackCrash.snapshot().messages.single { it.id == response }.lifecycle)

        requireSuccess(afterPlaybackCrash.markHeard(response))
        val afterHeardRestart = DurableAgentRunStore(file)
        requireSuccess(afterHeardRestart.load())
        requireSuccess(afterHeardRestart.reconcileAfterRestart())
        val heard = afterHeardRestart.snapshot().messages.single { it.id == response }

        assertEquals(DurableMessageLifecycle.HEARD, heard.lifecycle)
        assertEquals(DurableMessageLifecycle.HEARD, requireSuccess(afterHeardRestart.returnPlaybackToPending(response)).lifecycle)
        assertTrue(afterHeardRestart.beginPlayback(response) is DurableAgentStoreResult.Failure)
    }

    @Test
    fun durableLedgerRetainsArbitraryConfigurationIdsButNeverReconstructsConversationContext() = withTemporaryDirectory { directory ->
        val file = File(directory, "agent-runs.json")
        val original = DurableAgentRunStore(file)
        val configuration = DurableAgentConfiguration(
            connectionProfileId = "profile/tenant:eu-west-1",
            modelId = "vendor.example/custom-model@2026-07",
            systemPrompt = "System-only prompt",
            configurationFingerprint = "fingerprint-1",
        )
        val run = admit(original, "arbitrary-id", configuration = configuration, text = "private outbound conversation text")
        requireSuccess(original.beginRun(run))
        requireSuccess(original.commitInboundResponse(run, AgentMessageId("response-arbitrary-id"), "private inbound conversation text", 2))

        val restarted = DurableAgentRunStore(file)
        requireSuccess(restarted.load())

        assertEquals(configuration, restarted.snapshot().runs.single().configuration)
        assertTrue(restarted.freshConversationContext("channel").isEmpty())
    }

    private fun admit(
        store: DurableAgentRunStore,
        suffix: String,
        channel: String = "channel",
        configuration: DurableAgentConfiguration = configuration(),
        text: String = "outbound-$suffix",
    ): AgentRunId {
        val run = AgentRunId("run-$suffix")
        val result = store.admit(
            messageId = AgentMessageId("message-$suffix"),
            runId = run,
            channelInstanceId = channel,
            conversationEpoch = 0,
            configurationEpoch = 0,
            configuration = configuration,
            text = text,
            admittedAtMillis = 1,
        )
        val admission = requireSuccess(result)
        if (admission !is DurableAdmission.Accepted) throw AssertionError("Expected first admission, got $admission")
        return run
    }

    private fun configuration() = DurableAgentConfiguration(
        connectionProfileId = "profile",
        modelId = "model",
        systemPrompt = "system prompt",
        configurationFingerprint = "configuration-fingerprint",
    )

    private fun <T> requireSuccess(result: DurableAgentStoreResult<T>): T = when (result) {
        is DurableAgentStoreResult.Success -> result.value
        is DurableAgentStoreResult.Failure -> throw AssertionError("Expected success, got $result")
    }

    private fun <T> withTemporaryDirectory(block: (File) -> T): T {
        val directory = createTempDirectory("durable-agent-store-test-").toFile()
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
