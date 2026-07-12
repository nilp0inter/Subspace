package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.channel.capability.TextDeliveryOutcome
import dev.nilp0inter.subspace.channel.capability.TextKeyRequest
import dev.nilp0inter.subspace.channel.capability.TextOutputCapability
import dev.nilp0inter.subspace.channel.capability.TextOutputKey
import dev.nilp0inter.subspace.channel.capability.TextOutputProfile
import dev.nilp0inter.subspace.channel.capability.TextOutputRequest
import dev.nilp0inter.subspace.model.AgentOperationId
import dev.nilp0inter.subspace.model.AgentRunId
import dev.nilp0inter.subspace.model.OpenAiToolArgumentSchema
import dev.nilp0inter.subspace.model.OpenAiToolArgumentValue
import dev.nilp0inter.subspace.model.OpenAiToolCall
import dev.nilp0inter.subspace.model.OpenAiToolDefinition
import dev.nilp0inter.subspace.model.OpenAiToolFailureReason
import dev.nilp0inter.subspace.model.OpenAiToolIndeterminateReason
import dev.nilp0inter.subspace.model.OpenAiToolName
import dev.nilp0inter.subspace.model.OpenAiToolOutcome
import dev.nilp0inter.subspace.model.OpenAiToolRejectionReason
import dev.nilp0inter.subspace.model.OpenAiToolResult
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The host-owned authority for the narrow, configured client-tool surface.
 *
 * A [ToolBrokerBinding] is constructed by the host from one channel's current configuration;
 * model input never supplies a profile, device, keymap, retry policy, or target identity.
 */
data class ToolBrokerBinding(
    val channelInstanceId: String,
    val keyboardEnabled: Boolean,
    val textOutputProfile: TextOutputProfile?,
    val textOutputAvailable: Boolean,
    val isCurrent: () -> Boolean,
) {
    init {
        require(channelInstanceId.isNotBlank()) { "Tool broker channel instance ID must not be blank" }
        require(!keyboardEnabled || textOutputProfile != null) { "Enabled keyboard tools require a text-output profile" }
    }

    val isAuthorized: Boolean
        get() = keyboardEnabled && textOutputProfile != null && textOutputAvailable && isCurrent()
}

/** Strict, language-neutral definitions rendered by the OpenAI adapter as function schemas. */
class AgentToolRegistry(
    private val maximumTextLength: Int,
) {
    init {
        require(maximumTextLength > 0) { "Maximum tool text length must be positive" }
    }

    fun advertisedTools(binding: ToolBrokerBinding): List<OpenAiToolDefinition> =
        if (binding.isAuthorized) keyboardTools else emptyList()

    fun isTextWithinLimit(text: String): Boolean = text.isNotEmpty() && text.length <= maximumTextLength

    companion object {
        val KeyboardTypeText = OpenAiToolName("keyboard_type_text")
        val KeyboardPressEnter = OpenAiToolName("keyboard_press_enter")

        private val keyboardTools = listOf(
            OpenAiToolDefinition(
                name = KeyboardTypeText,
                description = "Type the supplied text using this channel's configured keyboard output.",
                arguments = OpenAiToolArgumentSchema(
                    properties = listOf(
                        OpenAiToolArgumentSchema.Property(
                            name = "text",
                            kind = OpenAiToolArgumentSchema.Kind.TEXT,
                            required = true,
                            description = "Text to type exactly once.",
                        ),
                    ),
                    additionalPropertiesAllowed = false,
                ),
            ),
            OpenAiToolDefinition(
                name = KeyboardPressEnter,
                description = "Press Enter exactly once using this channel's configured keyboard output.",
                arguments = OpenAiToolArgumentSchema(emptyList(), additionalPropertiesAllowed = false),
            ),
        )
    }
}

/**
 * Persists every observed call before a possible native effect and serializes calls per run.
 * Stored terminal results are returned verbatim on duplicate delivery; a possibly started effect
 * is never reissued.
 */
class AgentToolBroker(
    private val registry: AgentToolRegistry,
    private val ledger: DurableAgentRunStore,
    private val textOutput: TextOutputCapability,
    private val operationId: () -> AgentOperationId,
    private val maximumCallsPerBatch: Int,
) {
    init {
        require(maximumCallsPerBatch > 0) { "Maximum tool calls per batch must be positive" }
    }

    private val runLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun executeSequentially(
        runId: AgentRunId,
        binding: ToolBrokerBinding,
        calls: List<OpenAiToolCall>,
    ): List<OpenAiToolResult> = runLocks.computeIfAbsent(runId.value) { Mutex() }.withLock {
        calls.mapIndexed { index, call ->
            if (index >= maximumCallsPerBatch) {
                rejected(call, OpenAiToolRejectionReason.POLICY_REFUSED)
            } else {
                executeOne(runId, binding, call)
            }
        }
    }

    private suspend fun executeOne(
        runId: AgentRunId,
        binding: ToolBrokerBinding,
        call: OpenAiToolCall,
    ): OpenAiToolResult {
        val run = ledger.snapshot().runs.firstOrNull { it.id == runId }
            ?: return rejected(call, OpenAiToolRejectionReason.STALE_OPERATION)
        if (run.channelInstanceId != binding.channelInstanceId) {
            return rejected(call, OpenAiToolRejectionReason.STALE_OPERATION)
        }

        val reservation = ledger.reserveToolCall(
            runId = runId,
            callId = call.id,
            toolName = call.name.value,
            argumentHash = argumentHash(call),
            operationId = operationId(),
        )
        when (reservation) {
            is DurableAgentStoreResult.Failure -> return when (reservation.error) {
                is DurableAgentStoreFailure.ToolCallConflict -> rejected(call, OpenAiToolRejectionReason.INVALID_ARGUMENTS)
                else -> failed(call, OpenAiToolFailureReason.HOST_FAILURE)
            }
            is DurableAgentStoreResult.Success -> when (val value = reservation.value) {
                is DurableToolReservation.Existing -> return existingResult(call, value.entry)
                is DurableToolReservation.Reserved -> Unit
            }
        }

        val validation = validate(call, binding)
        if (validation != null) return record(call, runId, validation)
        if (!binding.isAuthorized) return record(call, runId, rejected(call, OpenAiToolRejectionReason.STALE_OPERATION))

        when (ledger.markToolEffectStarted(runId, call.id)) {
            is DurableAgentStoreResult.Failure -> return indeterminate(call, OpenAiToolIndeterminateReason.DELIVERY_UNCONFIRMED)
            is DurableAgentStoreResult.Success -> Unit
        }

        // Recheck at the final effect boundary: configuration replacement revokes queued calls.
        if (!binding.isAuthorized) return record(call, runId, rejected(call, OpenAiToolRejectionReason.STALE_OPERATION))
        val result = try {
            when (call.name) {
                AgentToolRegistry.KeyboardTypeText -> textOutput.sendText(
                    TextOutputRequest((call.arguments["text"] as OpenAiToolArgumentValue.Text).value, binding.textOutputProfile!!),
                )
                AgentToolRegistry.KeyboardPressEnter -> textOutput.sendKey(
                    TextKeyRequest(TextOutputKey.ENTER, binding.textOutputProfile!!),
                )
                else -> error("validated unknown tool")
            }.toToolResult(call)
        } catch (_: Exception) {
            // A thrown transport failure does not prove that the native effect did not begin.
            indeterminate(call, OpenAiToolIndeterminateReason.DELIVERY_UNCONFIRMED)
        }
        return record(call, runId, result)
    }

    private fun validate(call: OpenAiToolCall, binding: ToolBrokerBinding): OpenAiToolResult? = when (call.name) {
        AgentToolRegistry.KeyboardTypeText -> when {
            !binding.keyboardEnabled -> rejected(call, OpenAiToolRejectionReason.DISABLED)
            !binding.textOutputAvailable || binding.textOutputProfile == null -> rejected(call, OpenAiToolRejectionReason.UNAVAILABLE)
            call.arguments.keys != setOf("text") -> rejected(call, OpenAiToolRejectionReason.INVALID_ARGUMENTS)
            call.arguments["text"] !is OpenAiToolArgumentValue.Text -> rejected(call, OpenAiToolRejectionReason.INVALID_ARGUMENTS)
            !registry.isTextWithinLimit((call.arguments["text"] as OpenAiToolArgumentValue.Text).value) -> rejected(call, OpenAiToolRejectionReason.INVALID_ARGUMENTS)
            else -> null
        }
        AgentToolRegistry.KeyboardPressEnter -> when {
            !binding.keyboardEnabled -> rejected(call, OpenAiToolRejectionReason.DISABLED)
            !binding.textOutputAvailable || binding.textOutputProfile == null -> rejected(call, OpenAiToolRejectionReason.UNAVAILABLE)
            call.arguments.isNotEmpty() -> rejected(call, OpenAiToolRejectionReason.INVALID_ARGUMENTS)
            else -> null
        }
        else -> rejected(call, OpenAiToolRejectionReason.UNKNOWN_TOOL)
    }

    private fun existingResult(call: OpenAiToolCall, entry: DurableToolCallLedgerEntry): OpenAiToolResult =
        entry.terminalResult?.toToolResult(call) ?: indeterminate(call, OpenAiToolIndeterminateReason.DELIVERY_UNCONFIRMED)

    private fun record(call: OpenAiToolCall, runId: AgentRunId, result: OpenAiToolResult): OpenAiToolResult {
        val durable = result.toDurable()
        return when (val recorded = ledger.recordToolResult(runId, call.id, durable)) {
            is DurableAgentStoreResult.Success -> recorded.value.terminalResult?.toToolResult(call) ?: result
            is DurableAgentStoreResult.Failure -> indeterminate(call, OpenAiToolIndeterminateReason.DELIVERY_UNCONFIRMED)
        }
    }

    private fun TextDeliveryOutcome.toToolResult(call: OpenAiToolCall): OpenAiToolResult = when (this) {
        is TextDeliveryOutcome.Delivered -> delivered(call)
        is TextDeliveryOutcome.Rejected -> rejected(call, OpenAiToolRejectionReason.POLICY_REFUSED)
        is TextDeliveryOutcome.Failed -> when (reason.name) {
            "CANCELLED" -> cancelled(call)
            "TIMED_OUT" -> failed(call, OpenAiToolFailureReason.TIMED_OUT)
            else -> failed(call, OpenAiToolFailureReason.HOST_FAILURE)
        }
        is TextDeliveryOutcome.Indeterminate -> when (reason.name) {
            "CANCELLED" -> indeterminate(call, OpenAiToolIndeterminateReason.CANCELLED_DURING_EFFECT)
            "TIMED_OUT" -> indeterminate(call, OpenAiToolIndeterminateReason.TIMED_OUT)
            else -> indeterminate(call, OpenAiToolIndeterminateReason.DELIVERY_UNCONFIRMED)
        }
    }

    private fun OpenAiToolResult.toDurable(): DurableToolResult = when (val value = outcome) {
        OpenAiToolOutcome.Delivered -> DurableToolResult(DurableToolOutcome.DELIVERED, "delivered")
        is OpenAiToolOutcome.Rejected -> DurableToolResult(DurableToolOutcome.REJECTED, "rejected:${value.reason.name}")
        is OpenAiToolOutcome.Failed -> DurableToolResult(DurableToolOutcome.FAILED, "failed:${value.reason.name}")
        OpenAiToolOutcome.Cancelled -> DurableToolResult(DurableToolOutcome.CANCELLED, "cancelled")
        is OpenAiToolOutcome.Indeterminate -> DurableToolResult(DurableToolOutcome.INDETERMINATE, "indeterminate:${value.reason.name}")
    }

    private fun DurableToolResult.toToolResult(call: OpenAiToolCall): OpenAiToolResult = when (outcome) {
        DurableToolOutcome.DELIVERED -> delivered(call)
        DurableToolOutcome.REJECTED -> rejected(call, parseRejection(detail))
        DurableToolOutcome.FAILED -> failed(call, parseFailure(detail))
        DurableToolOutcome.CANCELLED -> cancelled(call)
        DurableToolOutcome.INDETERMINATE -> indeterminate(call, parseIndeterminate(detail))
    }

    private fun parseRejection(detail: String): OpenAiToolRejectionReason =
        runCatching { OpenAiToolRejectionReason.valueOf(detail.substringAfter(':')) }
            .getOrDefault(OpenAiToolRejectionReason.POLICY_REFUSED)

    private fun parseFailure(detail: String): OpenAiToolFailureReason =
        runCatching { OpenAiToolFailureReason.valueOf(detail.substringAfter(':')) }
            .getOrDefault(OpenAiToolFailureReason.HOST_FAILURE)

    private fun parseIndeterminate(detail: String): OpenAiToolIndeterminateReason =
        runCatching { OpenAiToolIndeterminateReason.valueOf(detail.substringAfter(':')) }
            .getOrDefault(OpenAiToolIndeterminateReason.DELIVERY_UNCONFIRMED)

    private fun argumentHash(call: OpenAiToolCall): String {
        val canonical = buildString {
            append(call.name.value.length).append(':').append(call.name.value)
            call.arguments.toSortedMap().forEach { (key, value) ->
                append('|').append(key.length).append(':').append(key).append('=')
                when (value) {
                    is OpenAiToolArgumentValue.Text -> append("text:").append(value.value.length).append(':').append(value.value)
                    is OpenAiToolArgumentValue.BooleanValue -> append("bool:").append(value.value)
                    is OpenAiToolArgumentValue.IntegerValue -> append("int:").append(value.value)
                    is OpenAiToolArgumentValue.NumberValue -> append("number:").append(value.value)
                }
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun delivered(call: OpenAiToolCall) = OpenAiToolResult(call.id, OpenAiToolOutcome.Delivered)
    private fun cancelled(call: OpenAiToolCall) = OpenAiToolResult(call.id, OpenAiToolOutcome.Cancelled)
    private fun rejected(call: OpenAiToolCall, reason: OpenAiToolRejectionReason) = OpenAiToolResult(call.id, OpenAiToolOutcome.Rejected(reason))
    private fun failed(call: OpenAiToolCall, reason: OpenAiToolFailureReason) = OpenAiToolResult(call.id, OpenAiToolOutcome.Failed(reason))
    private fun indeterminate(call: OpenAiToolCall, reason: OpenAiToolIndeterminateReason) = OpenAiToolResult(call.id, OpenAiToolOutcome.Indeterminate(reason))
}
