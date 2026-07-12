package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.channel.capability.AgentOperationContext
import dev.nilp0inter.subspace.channel.capability.CapabilityOperationResult
import dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity
import dev.nilp0inter.subspace.channel.capability.DelayedPlaybackCapability
import dev.nilp0inter.subspace.channel.capability.OpenAiCompletionCapability
import dev.nilp0inter.subspace.channel.capability.RuntimeGeneration
import dev.nilp0inter.subspace.model.AgentConversationEnqueueRequest
import dev.nilp0inter.subspace.model.AgentRunState
import dev.nilp0inter.subspace.model.DelayedPlaybackOutcome
import dev.nilp0inter.subspace.model.DelayedPlaybackOperationId
import dev.nilp0inter.subspace.model.DelayedPlaybackRequest
import dev.nilp0inter.subspace.model.OpenAiChatOutcome
import dev.nilp0inter.subspace.model.OpenAiChatRequest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunCoordinatorTest {
    @Test
    fun oneChannelIsSerializedWhileAnotherChannelCompletesIndependently() = runTest {
        withTemporaryDirectory { directory ->
            val firstMayFinish = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val completion = object : OpenAiCompletionCapability {
                override suspend fun complete(context: AgentOperationContext, request: OpenAiChatRequest): OpenAiChatOutcome {
                    events += "${context.scope.channelInstanceId}:${request.messages.filterIsInstance<dev.nilp0inter.subspace.model.OpenAiMessage.User>().last().text}"
                    if (context.scope.channelInstanceId == "one" && events.count { it.startsWith("one:") } == 1) firstMayFinish.await()
                    return OpenAiChatOutcome.FinalAssistantMessage("answer")
                }
            }
            val store = DurableAgentRunStore(File(directory, "runs.json"))
            val coordinator = coordinator(this, store, completion)
            val one = scope("one")
            val two = scope("two")

            assertTrue(coordinator.enqueue(one, request("first")) is CapabilityOperationResult.Success)
            runCurrent()
            assertTrue(coordinator.enqueue(one, request("second")) is CapabilityOperationResult.Success)
            assertTrue(coordinator.enqueue(two, request("other")) is CapabilityOperationResult.Success)
            runCurrent()

            assertEquals(listOf("one:first", "two:other"), events)
            firstMayFinish.complete(Unit)
            runCurrent()
            assertEquals(listOf("one:first", "two:other", "one:second"), events)
            assertEquals(AgentRunState.COMPLETED, coordinator.status.value.getValue("one").state)
            assertEquals(AgentRunState.COMPLETED, coordinator.status.value.getValue("two").state)
            coordinator.shutdown()
        }
    }

    @Test
    fun replacementCancelsOldEpochAndSuppressesItsLateCompletionAndPlayback() = runTest {
        withTemporaryDirectory { directory ->
            val started = CompletableDeferred<Unit>()
            val completion = object : OpenAiCompletionCapability {
                override suspend fun complete(context: AgentOperationContext, request: OpenAiChatRequest): OpenAiChatOutcome {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
            val playbackRequests = mutableListOf<DelayedPlaybackRequest>()
            val store = DurableAgentRunStore(File(directory, "runs.json"))
            val coordinator = coordinator(this, store, completion, playbackRequests)
            val channel = scope("replaced")

            assertTrue(coordinator.enqueue(channel, request("old request")) is CapabilityOperationResult.Success)
            runCurrent()
            assertTrue(started.isCompleted)
            coordinator.replace(channel)
            runCurrent()

            assertTrue(store.snapshot().runs.single().state.isTerminal)
            assertEquals(AgentRunState.INDETERMINATE, coordinator.status.value.getValue("replaced").state)
            assertTrue(playbackRequests.isEmpty())
            coordinator.shutdown()
        }
    }

    @Test
    fun restartResumesQueuedWorkWithFreshConversationContext() = runTest {
        withTemporaryDirectory { directory ->
            val firstStarted = CompletableDeferred<Unit>()
            val observedRequests = mutableListOf<List<dev.nilp0inter.subspace.model.OpenAiMessage>>()
            val completion = object : OpenAiCompletionCapability {
                override suspend fun complete(context: AgentOperationContext, request: OpenAiChatRequest): OpenAiChatOutcome {
                    observedRequests += request.messages
                    val user = request.messages.filterIsInstance<dev.nilp0inter.subspace.model.OpenAiMessage.User>().last().text
                    if (user == "first") {
                        firstStarted.complete(Unit)
                        awaitCancellation()
                    }
                    return OpenAiChatOutcome.FinalAssistantMessage("resumed")
                }
            }
            val store = DurableAgentRunStore(File(directory, "runs.json"))
            val channel = scope("restart")
            val beforeRestart = coordinator(this, store, completion)
            assertTrue(beforeRestart.enqueue(channel, request("first")) is CapabilityOperationResult.Success)
            runCurrent()
            assertTrue(firstStarted.isCompleted)
            assertTrue(beforeRestart.enqueue(channel, request("second")) is CapabilityOperationResult.Success)
            beforeRestart.shutdown()

            val afterRestart = coordinator(this, store, completion)
            afterRestart.start()
            runCurrent()

            val resumed = observedRequests.last()
            assertEquals(
                listOf(
                    dev.nilp0inter.subspace.model.OpenAiMessage.System("system"),
                    dev.nilp0inter.subspace.model.OpenAiMessage.User("second"),
                ),
                resumed,
            )
            afterRestart.shutdown()
        }
    }

    @Test
    fun unavailableCompletionTerminatesTheRunWithoutPublishingPlayback() = runTest {
        withTemporaryDirectory { directory ->
            val playbackRequests = mutableListOf<DelayedPlaybackRequest>()
            val completion = object : OpenAiCompletionCapability {
                override suspend fun complete(context: AgentOperationContext, request: OpenAiChatRequest): OpenAiChatOutcome =
                    OpenAiChatOutcome.Unavailable(dev.nilp0inter.subspace.model.OpenAiAvailabilityReason.AUTHENTICATION_FAILED)
            }
            val store = DurableAgentRunStore(File(directory, "runs.json"))
            val coordinator = coordinator(this, store, completion, playbackRequests)

            assertTrue(coordinator.enqueue(scope("failed"), request("request")) is CapabilityOperationResult.Success)
            runCurrent()

            assertEquals(AgentRunState.FAILED, coordinator.status.value.getValue("failed").state)
            assertTrue(playbackRequests.isEmpty())
            coordinator.shutdown()
        }
    }

    @Test
    fun admissionRejectsOversizeTextBeforeItCanEnterDurableOrRemoteWork() = runTest {
        withTemporaryDirectory { directory ->
            var completionCalled = false
            val completion = object : OpenAiCompletionCapability {
                override suspend fun complete(context: AgentOperationContext, request: OpenAiChatRequest): OpenAiChatOutcome {
                    completionCalled = true
                    return OpenAiChatOutcome.FinalAssistantMessage("unexpected")
                }
            }
            val store = DurableAgentRunStore(File(directory, "runs.json"))
            val coordinator = coordinator(this, store, completion, limits = AgentRunLimits(maximumUserTextBytes = 3, maximumRequestBytes = 20, maximumAssistantTextBytes = 20, maximumModelTurns = 1, maximumToolCalls = 1, operationTimeoutMillis = 10, maximumRunElapsedMillis = 10))

            assertEquals(CapabilityOperationResult.Failed(dev.nilp0inter.subspace.channel.capability.CapabilityFailureReason.INVALID_REQUEST), coordinator.enqueue(scope("bounded"), request("four")))
            runCurrent()
            assertTrue(store.snapshot().runs.isEmpty())
            assertFalse(completionCalled)
            coordinator.shutdown()
        }
    }

    private fun coordinator(
        scope: CoroutineScope,
        store: DurableAgentRunStore,
        completion: OpenAiCompletionCapability,
        playbackRequests: MutableList<DelayedPlaybackRequest> = mutableListOf(),
        limits: AgentRunLimits = AgentRunLimits(maximumUserTextBytes = 100, maximumRequestBytes = 1_000, maximumAssistantTextBytes = 100, maximumModelTurns = 2, maximumToolCalls = 2, operationTimeoutMillis = 100, maximumRunElapsedMillis = 100),
    ): AgentRunCoordinator {
        var nextId = 0
        val resolver = AgentRunConfigurationResolver { AgentRunCoordinatorConfiguration(DurableAgentConfiguration("profile", "gateway/custom-model", "system", "fingerprint"), emptyList()) }
        return AgentRunCoordinator(
            scope = scope,
            store = store,
            configurationResolver = resolver,
            completion = completion,
            tools = AgentToolExecutionPort { _, call -> dev.nilp0inter.subspace.model.OpenAiToolResult(call.id, dev.nilp0inter.subspace.model.OpenAiToolOutcome.Delivered) },
            playback = object : DelayedPlaybackCapability {
                override suspend fun schedule(context: AgentOperationContext, request: DelayedPlaybackRequest): DelayedPlaybackOutcome { playbackRequests += request; return DelayedPlaybackOutcome.Heard(DelayedPlaybackOperationId("heard-operation")) }
            },
            limits = limits,
            nowMillis = { 0L },
            newId = { "id-${++nextId}" },
        )
    }

    private fun scope(channel: String) = CapabilityScopeIdentity(channel, RuntimeGeneration(1))
    private fun request(text: String) = AgentConversationEnqueueRequest(dev.nilp0inter.subspace.model.OpenAiConnectionProfileId("profile"), dev.nilp0inter.subspace.model.OpenAiModelId("gateway/custom-model"), text)
    private suspend fun <T> withTemporaryDirectory(block: suspend (File) -> T): T { val directory = createTempDirectory("agent-run-coordinator-test-").toFile(); return try { block(directory) } finally { directory.deleteRecursively() } }
}
