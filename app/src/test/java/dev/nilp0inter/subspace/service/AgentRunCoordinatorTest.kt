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
    fun modelToolLoopStopsAtConfiguredTurnBoundInsteadOfDispatchingUnboundedCalls() = runTest {
        withTemporaryDirectory { directory ->
            var completionCalls = 0
            val completion = object : OpenAiCompletionCapability {
                override suspend fun complete(context: AgentOperationContext, request: OpenAiChatRequest): OpenAiChatOutcome {
                    completionCalls += 1
                    return OpenAiChatOutcome.ToolCalls(
                        listOf(
                            dev.nilp0inter.subspace.model.OpenAiToolCall(
                                dev.nilp0inter.subspace.model.AgentToolCallId("call-$completionCalls"),
                                dev.nilp0inter.subspace.model.OpenAiToolName("local_tool"),
                                emptyMap(),
                            ),
                        ),
                    )
                }
            }
            val store = DurableAgentRunStore(File(directory, "runs.json"))
            val limits = AgentRunLimits(maximumUserTextBytes = 100, maximumRequestBytes = 1_000, maximumAssistantTextBytes = 100, maximumModelTurns = 1, maximumToolCalls = 4, operationTimeoutMillis = 100, maximumRunElapsedMillis = 100)
            val coordinator = coordinator(this, store, completion, limits = limits)

            assertTrue(coordinator.enqueue(scope("bounded-loop"), request("request")) is CapabilityOperationResult.Success)
            runCurrent()

            assertEquals(1, completionCalls)
            assertEquals(AgentRunState.FAILED, coordinator.status.value.getValue("bounded-loop").state)
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
    @Test
    fun restartRecoveryCompletionToolAndPlaybackShareOneFreshScope() = runTest {
        withTemporaryDirectory { directory ->
            val preRestartScopeCapture = CompletableDeferred<CapabilityScopeIdentity>()
            val recoveredCompletionScopeCapture = CompletableDeferred<CapabilityScopeIdentity>()
            val recoveredToolScopeCapture = CompletableDeferred<CapabilityScopeIdentity>()
            val recoveredPlaybackScopeCapture = CompletableDeferred<CapabilityScopeIdentity>()
            var callCount = 0
            val completion = object : OpenAiCompletionCapability {
                override suspend fun complete(context: AgentOperationContext, request: OpenAiChatRequest): OpenAiChatOutcome {
                    val user = request.messages.filterIsInstance<dev.nilp0inter.subspace.model.OpenAiMessage.User>().last().text
                    if (user == "first") {
                        preRestartScopeCapture.complete(context.scope)
                        awaitCancellation()
                    }
                    recoveredCompletionScopeCapture.complete(context.scope)
                    // Recovered run: return tool calls then final message on second turn
                    return if (callCount++ == 0) {
                        OpenAiChatOutcome.ToolCalls(
                            listOf(
                                dev.nilp0inter.subspace.model.OpenAiToolCall(
                                    dev.nilp0inter.subspace.model.AgentToolCallId("call-1"),
                                    dev.nilp0inter.subspace.model.OpenAiToolName("test_tool"),
                                    emptyMap(),
                                ),
                            ),
                        )
                    } else {
                        OpenAiChatOutcome.FinalAssistantMessage("done")
                    }
                }
            }
            val store = DurableAgentRunStore(File(directory, "runs.json"))
            val channel = scope("restart")
            val beforeRestart = AgentRunCoordinator(
                scope = this,
                store = store,
                configurationResolver = AgentRunConfigurationResolver { AgentRunCoordinatorConfiguration(DurableAgentConfiguration("profile", "gateway/custom-model", "system", "fingerprint"), emptyList()) },
                completion = completion,
                tools = AgentToolExecutionPort { context, call ->
                    recoveredToolScopeCapture.complete(context.scope)
                    dev.nilp0inter.subspace.model.OpenAiToolResult(call.id, dev.nilp0inter.subspace.model.OpenAiToolOutcome.Delivered)
                },
                playback = object : DelayedPlaybackCapability {
                    override suspend fun schedule(context: AgentOperationContext, request: DelayedPlaybackRequest): DelayedPlaybackOutcome {
                        recoveredPlaybackScopeCapture.complete(context.scope)
                        return DelayedPlaybackOutcome.Heard(DelayedPlaybackOperationId("heard"))
                    }
                },
                limits = AgentRunLimits(maximumUserTextBytes = 100, maximumRequestBytes = 1_000, maximumAssistantTextBytes = 100, maximumModelTurns = 2, maximumToolCalls = 2, operationTimeoutMillis = 100, maximumRunElapsedMillis = 100),
                nowMillis = { 0L },
                newId = { java.util.UUID.randomUUID().toString() },
            )
            assertTrue(beforeRestart.enqueue(channel, request("first")) is CapabilityOperationResult.Success)
            runCurrent()
            assertTrue(preRestartScopeCapture.isCompleted)
            assertTrue(beforeRestart.enqueue(channel, request("second")) is CapabilityOperationResult.Success)
            beforeRestart.shutdown()

            val afterRestart = AgentRunCoordinator(
                scope = this,
                store = store,
                configurationResolver = AgentRunConfigurationResolver { AgentRunCoordinatorConfiguration(DurableAgentConfiguration("profile", "gateway/custom-model", "system", "fingerprint"), emptyList()) },
                completion = completion,
                tools = AgentToolExecutionPort { context, call ->
                    recoveredToolScopeCapture.complete(context.scope)
                    dev.nilp0inter.subspace.model.OpenAiToolResult(call.id, dev.nilp0inter.subspace.model.OpenAiToolOutcome.Delivered)
                },
                playback = object : DelayedPlaybackCapability {
                    override suspend fun schedule(context: AgentOperationContext, request: DelayedPlaybackRequest): DelayedPlaybackOutcome {
                        recoveredPlaybackScopeCapture.complete(context.scope)
                        return DelayedPlaybackOutcome.Heard(DelayedPlaybackOperationId("heard"))
                    }
                },
                limits = AgentRunLimits(maximumUserTextBytes = 100, maximumRequestBytes = 1_000, maximumAssistantTextBytes = 100, maximumModelTurns = 2, maximumToolCalls = 2, operationTimeoutMillis = 100, maximumRunElapsedMillis = 100),
                nowMillis = { 0L },
                newId = { java.util.UUID.randomUUID().toString() },
            )
            afterRestart.start()
            runCurrent()

            val preRestartScope = preRestartScopeCapture.await()
            val recoveredCompletionScope = recoveredCompletionScopeCapture.await()
            val recoveredToolScope = recoveredToolScopeCapture.await()
            val recoveredPlaybackScope = recoveredPlaybackScopeCapture.await()
            // Consistency: all three recovered contexts share the same scope
            assertEquals("completion and tool scopes must match", recoveredCompletionScope, recoveredToolScope)
            assertEquals("completion and playback scopes must match", recoveredCompletionScope, recoveredPlaybackScope)
            // Fresh: recovered scope differs from the pre-restart scope (not reused)
            assertFalse("recovered scope must be fresh, not the pre-restart scope", preRestartScope == recoveredCompletionScope)
            // Channel identity preserved
            assertEquals("channel identity preserved", "restart", recoveredCompletionScope.channelInstanceId)
            afterRestart.shutdown()
        }
    }

    @Test
    fun sequentialRestartRecoveriesAllocateDistinctScopes() = runTest {
        withTemporaryDirectory { directory ->
            val firstRecoveryScopeCapture = CompletableDeferred<CapabilityScopeIdentity>()
            val secondRecoveryScopeCapture = CompletableDeferred<CapabilityScopeIdentity>()
            val completion = object : OpenAiCompletionCapability {
                override suspend fun complete(context: AgentOperationContext, request: OpenAiChatRequest): OpenAiChatOutcome {
                    val user = request.messages.filterIsInstance<dev.nilp0inter.subspace.model.OpenAiMessage.User>().last().text
                    if (user == "block-a") {
                        awaitCancellation()
                    }
                    if (user == "q-a") {
                        firstRecoveryScopeCapture.complete(context.scope)
                        return OpenAiChatOutcome.FinalAssistantMessage("a")
                    }
                    if (user == "q-b") {
                        secondRecoveryScopeCapture.complete(context.scope)
                        return OpenAiChatOutcome.FinalAssistantMessage("b")
                    }
                    return OpenAiChatOutcome.FinalAssistantMessage("unreachable")
                }
            }
            val store = DurableAgentRunStore(File(directory, "runs.json"))
            val channel = scope("restart-multi")

            // First restart cycle: block the head, queue resumable work behind it, shut down.
            val first = coordinator(this, store, completion)
            assertTrue(first.enqueue(channel, request("block-a")) is CapabilityOperationResult.Success)
            runCurrent()
            assertTrue(first.enqueue(channel, request("q-a")) is CapabilityOperationResult.Success)
            first.shutdown()

            // Recovery A: start() recovers the queued "q-a" with a fresh scope.
            val recoveredA = coordinator(this, store, completion)
            recoveredA.start()
            runCurrent()
            val firstScope = firstRecoveryScopeCapture.await()
            recoveredA.shutdown()

            // Second restart cycle: again block the head, queue resumable work, shut down.
            val second = coordinator(this, store, completion)
            assertTrue(second.enqueue(channel, request("block-b")) is CapabilityOperationResult.Success)
            runCurrent()
            assertTrue(second.enqueue(channel, request("q-b")) is CapabilityOperationResult.Success)
            second.shutdown()

            // Recovery B: start() recovers the queued "q-b" with a second fresh scope.
            val recoveredB = coordinator(this, store, completion)
            recoveredB.start()
            runCurrent()
            val secondScope = secondRecoveryScopeCapture.await()
            recoveredB.shutdown()

            // Non-colliding: two recovery generations allocate distinct scopes.
            assertFalse("sequential recoveries must allocate distinct scopes", firstScope == secondScope)
        }
    }

    @Test
    fun oldGenerationLeaseRacingAfterReplaceIsRejectedWhileFreshSuccessorBinds() = runTest {
        withTemporaryDirectory { directory ->
            val started = CompletableDeferred<Unit>()
            val completionCalls = mutableListOf<Pair<CapabilityScopeIdentity, String>>()
            val toolScopes = mutableListOf<CapabilityScopeIdentity>()
            val playbackScopes = mutableListOf<CapabilityScopeIdentity>()
            var successorTurn = 0
            val completion = object : OpenAiCompletionCapability {
                override suspend fun complete(context: AgentOperationContext, request: OpenAiChatRequest): OpenAiChatOutcome {
                    val user = request.messages.filterIsInstance<dev.nilp0inter.subspace.model.OpenAiMessage.User>().last().text
                    completionCalls += context.scope to user
                    if (user == "old request") {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                    // Fresh successor H: tool calls on first turn, final message on second.
                    return if (user == "fresh successor" && successorTurn++ == 0) {
                        OpenAiChatOutcome.ToolCalls(
                            listOf(
                                dev.nilp0inter.subspace.model.OpenAiToolCall(
                                    dev.nilp0inter.subspace.model.AgentToolCallId("call-1"),
                                    dev.nilp0inter.subspace.model.OpenAiToolName("test_tool"),
                                    emptyMap(),
                                ),
                            ),
                        )
                    } else {
                        OpenAiChatOutcome.FinalAssistantMessage("done")
                    }
                }
            }
            val store = DurableAgentRunStore(File(directory, "runs.json"))
            val coordinator = AgentRunCoordinator(
                scope = this,
                store = store,
                configurationResolver = AgentRunConfigurationResolver { AgentRunCoordinatorConfiguration(DurableAgentConfiguration("profile", "gateway/custom-model", "system", "fingerprint"), emptyList()) },
                completion = completion,
                tools = AgentToolExecutionPort { context, call ->
                    toolScopes += context.scope
                    dev.nilp0inter.subspace.model.OpenAiToolResult(call.id, dev.nilp0inter.subspace.model.OpenAiToolOutcome.Delivered)
                },
                playback = object : DelayedPlaybackCapability {
                    override suspend fun schedule(context: AgentOperationContext, request: DelayedPlaybackRequest): DelayedPlaybackOutcome {
                        playbackScopes += context.scope
                        return DelayedPlaybackOutcome.Heard(DelayedPlaybackOperationId("heard"))
                    }
                },
                limits = AgentRunLimits(maximumUserTextBytes = 100, maximumRequestBytes = 1_000, maximumAssistantTextBytes = 100, maximumModelTurns = 2, maximumToolCalls = 2, operationTimeoutMillis = 100, maximumRunElapsedMillis = 100),
                nowMillis = { 0L },
                newId = { java.util.UUID.randomUUID().toString() },
            )
            // Explicit generation relationship: H is strictly newer than retired G, not the helper's default generation.
            val generationG = RuntimeGeneration(7)
            val generationH = RuntimeGeneration(generationG.value + 1)
            assertTrue("H must be strictly newer than G", generationH.value > generationG.value)
            val scopeG = CapabilityScopeIdentity("race", generationG)
            val scopeH = CapabilityScopeIdentity("race", generationH)

            // Establish G and let its remote completion start, then retire G via replace.
            assertTrue(coordinator.enqueue(scopeG, request("old request")) is CapabilityOperationResult.Success)
            runCurrent()
            assertTrue(started.isCompleted)
            coordinator.replace(scopeG)
            runCurrent()
            val runsAfterReplace = store.snapshot().runs
            assertTrue("retired G run must be terminal", runsAfterReplace.single().state.isTerminal)

            // Old-G lease racing after replace: typed rejection, zero new durable/remote/tool/playback work.
            assertEquals("old generation must be closed after replace", CapabilityOperationResult.Closed, coordinator.enqueue(scopeG, request("late old lease")))
            runCurrent()
            assertEquals("no new durable run admitted for rejected old lease", runsAfterReplace.size, store.snapshot().runs.size)
            assertFalse("rejected old lease must not reach remote completion", completionCalls.any { it.second == "late old lease" })
            assertTrue("rejected old lease must not start tool execution", toolScopes.isEmpty())
            assertTrue("rejected old lease must not start playback", playbackScopes.isEmpty())

            // Fresh successor H binds the lifecycle and succeeds; all operation contexts use H.
            assertTrue(coordinator.enqueue(scopeH, request("fresh successor")) is CapabilityOperationResult.Success)
            runCurrent()
            val successorCompletionScopes = completionCalls.filter { it.second == "fresh successor" }.map { it.first }
            assertTrue("successor must perform completion", successorCompletionScopes.isNotEmpty())
            assertTrue("all completion contexts must use successor scope H", successorCompletionScopes.all { it == scopeH })
            assertEquals("tool context must use successor scope H", scopeH, toolScopes.single())
            assertEquals("playback context must use successor scope H", scopeH, playbackScopes.single())

            // Later G still rejected and cannot overwrite H.
            assertEquals("stale G must remain closed after H binds", CapabilityOperationResult.Closed, coordinator.enqueue(scopeG, request("stale again")))
            runCurrent()
            assertFalse("stale G must not reach remote completion", completionCalls.any { it.second == "stale again" })
            // Equal-current H is accepted (the bound successor generation), proving G did not overwrite H.
            assertTrue("equal-current successor H must be accepted", coordinator.enqueue(scopeH, request("equal current")) is CapabilityOperationResult.Success)
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
