package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.channel.capability.TextDeliveryOutcome
import dev.nilp0inter.subspace.channel.capability.TextKeyRequest
import dev.nilp0inter.subspace.channel.capability.TextOutputCapability
import dev.nilp0inter.subspace.channel.capability.TextOutputKey
import dev.nilp0inter.subspace.channel.capability.TextOutputProfile
import dev.nilp0inter.subspace.channel.capability.TextOutputRequest
import dev.nilp0inter.subspace.model.AgentMessageId
import dev.nilp0inter.subspace.model.AgentOperationId
import dev.nilp0inter.subspace.model.AgentRunId
import dev.nilp0inter.subspace.model.AgentToolCallId
import dev.nilp0inter.subspace.model.OpenAiToolArgumentValue
import dev.nilp0inter.subspace.model.OpenAiToolCall
import dev.nilp0inter.subspace.model.OpenAiToolOutcome
import dev.nilp0inter.subspace.model.OpenAiToolRejectionReason
import dev.nilp0inter.subspace.model.OpenAiToolResult
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolBrokerTest {
    @Test
    fun advertisedToolsExposeOnlyStrictAuthorizedKeyboardSchemas() {
        val registry = AgentToolRegistry(maximumTextLength = 3)
        val enabled = binding()
        val unavailable = binding(textOutputAvailable = false)

        val advertised = registry.advertisedTools(enabled)
        val text = advertised.single { it.name == AgentToolRegistry.KeyboardTypeText }
        val enter = advertised.single { it.name == AgentToolRegistry.KeyboardPressEnter }

        assertEquals(setOf(AgentToolRegistry.KeyboardTypeText, AgentToolRegistry.KeyboardPressEnter), advertised.map { it.name }.toSet())
        assertEquals(false, text.arguments.additionalPropertiesAllowed)
        assertEquals(
            listOf(
                dev.nilp0inter.subspace.model.OpenAiToolArgumentSchema.Property(
                    name = "text",
                    kind = dev.nilp0inter.subspace.model.OpenAiToolArgumentSchema.Kind.TEXT,
                    required = true,
                    description = "Text to type exactly once.",
                ),
            ),
            text.arguments.properties,
        )
        assertEquals(emptyList<dev.nilp0inter.subspace.model.OpenAiToolArgumentSchema.Property>(), enter.arguments.properties)
        assertEquals(false, enter.arguments.additionalPropertiesAllowed)
        assertTrue(registry.advertisedTools(unavailable).isEmpty())
    }

    @Test
    fun disabledUnavailableUnknownAndInvalidCallsAreRejectedWithoutNativeEffects() = runTest {
        withTemporaryDirectory { directory ->
            val store = DurableAgentRunStore(File(directory, "ledger.json"))
            val output = RecordingTextOutput()
            val broker = broker(store, output, maximumTextLength = 3)
            val run = activeRun(store, "validation")
            val calls = listOf(
                binding(keyboardEnabled = false) to textCall("disabled", "ok"),
                binding(textOutputAvailable = false) to textCall("unavailable", "ok"),
                binding() to OpenAiToolCall(
                    AgentToolCallId("unknown"),
                    dev.nilp0inter.subspace.model.OpenAiToolName("not_a_keyboard_tool"),
                    emptyMap(),
                ),
                binding() to textCall("too-long", "four"),
                binding() to OpenAiToolCall(
                    AgentToolCallId("wrong-enter-arguments"),
                    AgentToolRegistry.KeyboardPressEnter,
                    mapOf("text" to OpenAiToolArgumentValue.Text("unexpected")),
                ),
            )

            val results = calls.mapSuspending { (binding, call) ->
                broker.executeSequentially(run, binding, listOf(call)).single()
            }

            assertEquals(
                listOf(
                    rejected("disabled", OpenAiToolRejectionReason.DISABLED),
                    rejected("unavailable", OpenAiToolRejectionReason.UNAVAILABLE),
                    rejected("unknown", OpenAiToolRejectionReason.UNKNOWN_TOOL),
                    rejected("too-long", OpenAiToolRejectionReason.INVALID_ARGUMENTS),
                    rejected("wrong-enter-arguments", OpenAiToolRejectionReason.INVALID_ARGUMENTS),
                ),
                results,
            )
            assertTrue(output.effects.isEmpty())
        }
    }

    @Test
    fun sequentialTextAndEnterCallsDeliverExactEffectsInModelOrder() = runTest {
        withTemporaryDirectory { directory ->
            val store = DurableAgentRunStore(File(directory, "ledger.json"))
            val output = RecordingTextOutput()
            val broker = broker(store, output, maximumTextLength = 3)
            val run = activeRun(store, "ordered")
            val profile = TextOutputProfile("keyboard-profile")
            val calls = listOf(
                textCall("type", "abc"),
                OpenAiToolCall(AgentToolCallId("enter"), AgentToolRegistry.KeyboardPressEnter, emptyMap()),
            )

            val results = broker.executeSequentially(run, binding(profile = profile), calls)

            assertEquals(
                listOf(
                    OpenAiToolResult(AgentToolCallId("type"), OpenAiToolOutcome.Delivered),
                    OpenAiToolResult(AgentToolCallId("enter"), OpenAiToolOutcome.Delivered),
                ),
                results,
            )
            assertEquals(
                listOf(
                    Effect.Text(TextOutputRequest("abc", profile)),
                    Effect.Key(TextKeyRequest(TextOutputKey.ENTER, profile)),
                ),
                output.effects,
            )
        }
    }

    @Test
    fun duplicateResultsAreResubmittedButConflictingReuseOfCallIdCannotIssueAnotherEffect() = runTest {
        withTemporaryDirectory { directory ->
            val store = DurableAgentRunStore(File(directory, "ledger.json"))
            val output = RecordingTextOutput()
            val broker = broker(store, output, maximumTextLength = 3)
            val run = activeRun(store, "duplicate")
            val original = textCall("same-id", "one")
            val conflicting = textCall("same-id", "two")

            val first = broker.executeSequentially(run, binding(), listOf(original)).single()
            val duplicate = broker.executeSequentially(run, binding(), listOf(original)).single()
            val conflict = broker.executeSequentially(run, binding(), listOf(conflicting)).single()

            assertEquals(OpenAiToolResult(original.id, OpenAiToolOutcome.Delivered), first)
            assertEquals(first, duplicate)
            assertEquals(rejected("same-id", OpenAiToolRejectionReason.INVALID_ARGUMENTS), conflict)
            assertEquals(listOf(Effect.Text(TextOutputRequest("one", TextOutputProfile("keyboard-profile")))), output.effects)
        }
    }

    @Test
    fun uncertainEffectIsStoredAsIndeterminateAndNeverReissuedOnResultResubmission() = runTest {
        withTemporaryDirectory { directory ->
            val store = DurableAgentRunStore(File(directory, "ledger.json"))
            val output = RecordingTextOutput(throwAfterEffect = true)
            val broker = broker(store, output, maximumTextLength = 3)
            val run = activeRun(store, "uncertain-effect")
            val call = textCall("uncertain", "one")

            val first = broker.executeSequentially(run, binding(), listOf(call)).single()
            val resubmission = broker.executeSequentially(run, binding(), listOf(call)).single()

            val expected = OpenAiToolResult(
                call.id,
                OpenAiToolOutcome.Indeterminate(dev.nilp0inter.subspace.model.OpenAiToolIndeterminateReason.DELIVERY_UNCONFIRMED),
            )
            assertEquals(expected, first)
            assertEquals(expected, resubmission)
            assertEquals(listOf(Effect.Text(TextOutputRequest("one", TextOutputProfile("keyboard-profile")))), output.effects)
            assertEquals(DurableToolCallState.RESULT_RECORDED, store.snapshot().toolCalls.single().state)
        }
    }

    @Test
    fun configurationRevocationAfterReservationPreventsTheNativeEffect() = runTest {
        withTemporaryDirectory { directory ->
            val store = DurableAgentRunStore(File(directory, "ledger.json"))
            val output = RecordingTextOutput()
            val broker = broker(store, output, maximumTextLength = 3)
            val run = activeRun(store, "revoked")
            var authorizationChecks = 0
            val revokedBeforeEffect = binding(isCurrent = { authorizationChecks++ == 0 })
            val call = textCall("revoked-call", "one")

            val result = broker.executeSequentially(run, revokedBeforeEffect, listOf(call)).single()

            assertEquals(rejected("revoked-call", OpenAiToolRejectionReason.STALE_OPERATION), result)
            assertTrue(output.effects.isEmpty())
            assertEquals(DurableToolCallState.RESULT_RECORDED, store.snapshot().toolCalls.single().state)
        }
    }

    @Test
    fun textDeliveryOutcomesArePersistedAsTheirExactModelSafeTerminalResults() = runTest {
        data class MappingCase(
            val name: String,
            val delivery: TextDeliveryOutcome,
            val expected: OpenAiToolOutcome,
        )
        val cases = listOf(
            MappingCase("delivered", TextDeliveryOutcome.Delivered("delivery-1"), OpenAiToolOutcome.Delivered),
            MappingCase(
                "rejected",
                TextDeliveryOutcome.Rejected("delivery-2", dev.nilp0inter.subspace.channel.capability.TextOutputRejectionReason.POLICY_REFUSED),
                OpenAiToolOutcome.Rejected(OpenAiToolRejectionReason.POLICY_REFUSED),
            ),
            MappingCase(
                "timed-out failure",
                TextDeliveryOutcome.Failed("delivery-3", dev.nilp0inter.subspace.channel.capability.TextOutputFailureReason.TIMED_OUT),
                OpenAiToolOutcome.Failed(dev.nilp0inter.subspace.model.OpenAiToolFailureReason.TIMED_OUT),
            ),
            MappingCase(
                "cancelled failure",
                TextDeliveryOutcome.Failed("delivery-4", dev.nilp0inter.subspace.channel.capability.TextOutputFailureReason.CANCELLED),
                OpenAiToolOutcome.Cancelled,
            ),
            MappingCase(
                "host failure",
                TextDeliveryOutcome.Failed("delivery-5", dev.nilp0inter.subspace.channel.capability.TextOutputFailureReason.HOST_FAILURE),
                OpenAiToolOutcome.Failed(dev.nilp0inter.subspace.model.OpenAiToolFailureReason.HOST_FAILURE),
            ),
            MappingCase(
                "lost acknowledgement",
                TextDeliveryOutcome.Indeterminate("delivery-6", dev.nilp0inter.subspace.channel.capability.TextOutputIndeterminateReason.ACKNOWLEDGEMENT_LOST),
                OpenAiToolOutcome.Indeterminate(dev.nilp0inter.subspace.model.OpenAiToolIndeterminateReason.DELIVERY_UNCONFIRMED),
            ),
            MappingCase(
                "timed out during effect",
                TextDeliveryOutcome.Indeterminate("delivery-7", dev.nilp0inter.subspace.channel.capability.TextOutputIndeterminateReason.TIMED_OUT),
                OpenAiToolOutcome.Indeterminate(dev.nilp0inter.subspace.model.OpenAiToolIndeterminateReason.TIMED_OUT),
            ),
            MappingCase(
                "cancelled during effect",
                TextDeliveryOutcome.Indeterminate("delivery-8", dev.nilp0inter.subspace.channel.capability.TextOutputIndeterminateReason.CANCELLED),
                OpenAiToolOutcome.Indeterminate(dev.nilp0inter.subspace.model.OpenAiToolIndeterminateReason.CANCELLED_DURING_EFFECT),
            ),
        )

        cases.forEachIndexedSuspending { index, mapping ->
            withTemporaryDirectory { directory ->
                val store = DurableAgentRunStore(File(directory, "ledger.json"))
                val output = OutcomeTextOutput(mapping.delivery)
                val broker = broker(store, output, maximumTextLength = 3)
                val run = activeRun(store, "mapping-$index")
                val call = textCall("mapping-$index", "one")

                val result = broker.executeSequentially(run, binding(), listOf(call)).single()

                assertEquals(OpenAiToolResult(call.id, mapping.expected), result)
                assertEquals(listOf(TextOutputRequest("one", TextOutputProfile("keyboard-profile"))), output.textRequests)
            }
        }
    }

    @Test
    fun callsBeyondTheBoundedSequentialBatchAreRefusedWithoutEffects() = runTest {
        withTemporaryDirectory { directory ->
            val store = DurableAgentRunStore(File(directory, "ledger.json"))
            val output = RecordingTextOutput()
            val broker = broker(store, output, maximumTextLength = 3)
            val run = activeRun(store, "batch-limit")
            val calls = (1..5).map { index -> textCall("batch-$index", "one") }

            val results = broker.executeSequentially(run, binding(), calls)

            assertEquals(
                listOf(
                    OpenAiToolResult(AgentToolCallId("batch-1"), OpenAiToolOutcome.Delivered),
                    OpenAiToolResult(AgentToolCallId("batch-2"), OpenAiToolOutcome.Delivered),
                    OpenAiToolResult(AgentToolCallId("batch-3"), OpenAiToolOutcome.Delivered),
                    OpenAiToolResult(AgentToolCallId("batch-4"), OpenAiToolOutcome.Delivered),
                    rejected("batch-5", OpenAiToolRejectionReason.POLICY_REFUSED),
                ),
                results,
            )
            assertEquals(
                listOf(
                    Effect.Text(TextOutputRequest("one", TextOutputProfile("keyboard-profile"))),
                    Effect.Text(TextOutputRequest("one", TextOutputProfile("keyboard-profile"))),
                    Effect.Text(TextOutputRequest("one", TextOutputProfile("keyboard-profile"))),
                    Effect.Text(TextOutputRequest("one", TextOutputProfile("keyboard-profile"))),
                ),
                output.effects,
            )
        }
    }

    @Test
    fun persistedDeliveredResultIsResubmittedAfterRestartWithoutReissuingTheNativeEffect() = runTest {
        withTemporaryDirectory { directory ->
            val file = File(directory, "ledger.json")
            val originalStore = DurableAgentRunStore(file)
            val originalOutput = RecordingTextOutput()
            val originalBroker = broker(originalStore, originalOutput, maximumTextLength = 3)
            val run = activeRun(originalStore, "restart-duplicate")
            val call = textCall("restart-call", "one")

            assertEquals(OpenAiToolResult(call.id, OpenAiToolOutcome.Delivered), originalBroker.executeSequentially(run, binding(), listOf(call)).single())

            val restartedStore = DurableAgentRunStore(file)
            requireSuccess(restartedStore.load())
            val restartedOutput = RecordingTextOutput()
            val restartedBroker = broker(restartedStore, restartedOutput, maximumTextLength = 3)

            val resubmission = restartedBroker.executeSequentially(run, binding(), listOf(call)).single()

            assertEquals(OpenAiToolResult(call.id, OpenAiToolOutcome.Delivered), resubmission)
            assertEquals(listOf(Effect.Text(TextOutputRequest("one", TextOutputProfile("keyboard-profile")))), originalOutput.effects)
            assertTrue(restartedOutput.effects.isEmpty())
        }
    }

    private fun broker(
        store: DurableAgentRunStore,
        output: TextOutputCapability,
        maximumTextLength: Int,
    ): AgentToolBroker {
        var nextOperation = 0
        return AgentToolBroker(
            registry = AgentToolRegistry(maximumTextLength),
            ledger = store,
            textOutput = output,
            operationId = { AgentOperationId("tool-operation-${nextOperation++}") },
            maximumCallsPerBatch = 4,
        )
    }

    private fun activeRun(store: DurableAgentRunStore, suffix: String): AgentRunId {
        val run = AgentRunId("run-$suffix")
        requireSuccess(
            store.admit(
                messageId = AgentMessageId("message-$suffix"),
                runId = run,
                channelInstanceId = "channel",
                conversationEpoch = 3,
                configurationEpoch = 5,
                configuration = DurableAgentConfiguration("profile", "model", "system", "fingerprint"),
                text = "request-$suffix",
                admittedAtMillis = 11,
            ),
        )
        requireSuccess(store.beginRun(run))
        return run
    }

    private fun binding(
        keyboardEnabled: Boolean = true,
        textOutputAvailable: Boolean = true,
        profile: TextOutputProfile = TextOutputProfile("keyboard-profile"),
        isCurrent: () -> Boolean = { true },
    ) = ToolBrokerBinding(
        channelInstanceId = "channel",
        keyboardEnabled = keyboardEnabled,
        textOutputProfile = profile,
        textOutputAvailable = textOutputAvailable,
        isCurrent = isCurrent,
    )

    private fun textCall(id: String, text: String) = OpenAiToolCall(
        id = AgentToolCallId(id),
        name = AgentToolRegistry.KeyboardTypeText,
        arguments = mapOf("text" to OpenAiToolArgumentValue.Text(text)),
    )

    private fun rejected(id: String, reason: OpenAiToolRejectionReason) = OpenAiToolResult(
        AgentToolCallId(id),
        OpenAiToolOutcome.Rejected(reason),
    )

    private fun <T> requireSuccess(result: DurableAgentStoreResult<T>): T = when (result) {
        is DurableAgentStoreResult.Success -> result.value
        is DurableAgentStoreResult.Failure -> throw AssertionError("Expected success, got $result")
    }

    private suspend fun <T> withTemporaryDirectory(block: suspend (File) -> T): T {
        val directory = createTempDirectory("agent-tool-broker-test-").toFile()
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private suspend fun <T, R> Iterable<T>.mapSuspending(transform: suspend (T) -> R): List<R> {
        val results = ArrayList<R>()
        for (element in this) results += transform(element)
        return results
    }

    private suspend fun <T> Iterable<T>.forEachIndexedSuspending(action: suspend (Int, T) -> Unit) {
        var index = 0
        for (element in this) action(index++, element)
    }

    private sealed interface Effect {
        data class Text(val request: TextOutputRequest) : Effect
        data class Key(val request: TextKeyRequest) : Effect
    }

    private class RecordingTextOutput(
        private val throwAfterEffect: Boolean = false,
    ) : TextOutputCapability {
        val effects = mutableListOf<Effect>()

        override suspend fun sendText(request: TextOutputRequest): TextDeliveryOutcome {
            effects += Effect.Text(request)
            if (throwAfterEffect) throw IllegalStateException("effect acknowledgement lost")
            return TextDeliveryOutcome.Delivered("text-${effects.size}")
        }

        override suspend fun sendKey(request: TextKeyRequest): TextDeliveryOutcome {
            effects += Effect.Key(request)
            if (throwAfterEffect) throw IllegalStateException("effect acknowledgement lost")
            return TextDeliveryOutcome.Delivered("key-${effects.size}")
        }
    }

    private class OutcomeTextOutput(
        private val outcome: TextDeliveryOutcome,
    ) : TextOutputCapability {
        val textRequests = mutableListOf<TextOutputRequest>()

        override suspend fun sendText(request: TextOutputRequest): TextDeliveryOutcome {
            textRequests += request
            return outcome
        }

        override suspend fun sendKey(request: TextKeyRequest): TextDeliveryOutcome =
            throw AssertionError("Enter was not requested by a text tool call")
    }
}
