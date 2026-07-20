package dev.nilp0inter.subspace.model

/** Stable host reference to one global OpenAI-compatible connection profile. */
@JvmInline
value class OpenAiConnectionProfileId(val value: String) {
    init {
        require(value.isNotBlank()) { "OpenAI connection profile ID must not be blank" }
    }
}

/** Stable host reference to one bearer credential held outside channel configuration. */
@JvmInline
value class OpenAiCredentialReference(val value: String) {
    init {
        require(value.isNotBlank()) { "OpenAI credential reference must not be blank" }
    }
}

/** Open model identifiers remain open strings; they are not a host enum. */
@JvmInline
value class OpenAiModelId(val value: String) {
    init {
        require(value.isNotBlank()) { "OpenAI model ID must not be blank" }
    }
}

/** Non-secret profile metadata. The credential value itself never crosses this boundary. */
data class OpenAiConnectionProfile(
    val id: OpenAiConnectionProfileId,
    val displayName: String,
    val baseUrl: String,
    val credentialReference: OpenAiCredentialReference,
) {
    init {
        require(displayName.isNotBlank()) { "OpenAI connection profile name must not be blank" }
        require(baseUrl.isNotBlank()) { "OpenAI connection profile base URL must not be blank" }
    }
}

data class OpenAiModelChoice(
    val id: OpenAiModelId,
    val label: String,
) {
    init {
        require(label.isNotBlank()) { "OpenAI model label must not be blank" }
    }
}

/** Host-normalized model discovery state for exactly one profile. */
sealed interface OpenAiModelDiscoveryOutcome {
    val profileId: OpenAiConnectionProfileId

    data class Loading(
        override val profileId: OpenAiConnectionProfileId,
    ) : OpenAiModelDiscoveryOutcome

    data class Available(
        override val profileId: OpenAiConnectionProfileId,
        val models: List<OpenAiModelChoice>,
        val isStale: Boolean = false,
    ) : OpenAiModelDiscoveryOutcome

    data class Unavailable(
        override val profileId: OpenAiConnectionProfileId,
        val reason: OpenAiAvailabilityReason,
    ) : OpenAiModelDiscoveryOutcome
}

enum class OpenAiAvailabilityReason {
    PROFILE_MISSING,
    PROFILE_DISABLED,
    CREDENTIAL_MISSING,
    AUTHENTICATION_FAILED,
    ENDPOINT_INVALID,
    UNREACHABLE,
    INVALID_RESPONSE,
    HOST_NOT_READY,
    CANCELLED,
}

@JvmInline
value class AgentMessageId(val value: String) {
    init {
        require(value.isNotBlank()) { "Agent message ID must not be blank" }
    }
}

@JvmInline
value class AgentRunId(val value: String) {
    init {
        require(value.isNotBlank()) { "Agent run ID must not be blank" }
    }
}

@JvmInline
value class AgentToolCallId(val value: String) {
    init {
        require(value.isNotBlank()) { "Agent tool call ID must not be blank" }
    }
}

@JvmInline
value class AgentOperationId(val value: String) {
    init {
        require(value.isNotBlank()) { "Agent operation ID must not be blank" }
    }
}

@JvmInline
value class DelayedPlaybackOperationId(val value: String) {
    init {
        require(value.isNotBlank()) { "Delayed playback operation ID must not be blank" }
    }
}

@JvmInline
value class OpenAiToolName(val value: String) {
    init {
        require(value.isNotBlank()) { "OpenAI tool name must not be blank" }
    }
}

sealed interface OpenAiToolArgumentValue {
    data class Text(val value: String) : OpenAiToolArgumentValue
    data class BooleanValue(val value: Boolean) : OpenAiToolArgumentValue
    data class IntegerValue(val value: Long) : OpenAiToolArgumentValue
    data class NumberValue(val value: Double) : OpenAiToolArgumentValue
}

/** Declarative, SDK-free tool schema. Host adapters render this as protocol-specific schema. */
data class OpenAiToolArgumentSchema(
    val properties: List<Property>,
    val additionalPropertiesAllowed: Boolean = false,
) {
    init {
        require(properties.map(Property::name).distinct().size == properties.size) {
            "OpenAI tool argument names must be unique"
        }
    }

    data class Property(
        val name: String,
        val kind: Kind,
        val required: Boolean,
        val description: String = "",
    ) {
        init {
            require(name.isNotBlank()) { "OpenAI tool argument name must not be blank" }
        }
    }

    enum class Kind {
        TEXT,
        BOOLEAN,
        INTEGER,
        NUMBER,
    }
}

data class OpenAiToolDefinition(
    val name: OpenAiToolName,
    val description: String,
    val arguments: OpenAiToolArgumentSchema,
) {
    init {
        require(description.isNotBlank()) { "OpenAI tool description must not be blank" }
    }
}

data class OpenAiToolCall(
    val id: AgentToolCallId,
    val name: OpenAiToolName,
    val arguments: Map<String, OpenAiToolArgumentValue>,
)

data class OpenAiToolResult(
    val callId: AgentToolCallId,
    val outcome: OpenAiToolOutcome,
    val data: Map<String, OpenAiToolArgumentValue> = emptyMap(),
)

sealed interface OpenAiToolOutcome {
    data object Delivered : OpenAiToolOutcome
    data class Rejected(val reason: OpenAiToolRejectionReason) : OpenAiToolOutcome
    data class Failed(val reason: OpenAiToolFailureReason) : OpenAiToolOutcome
    data object Cancelled : OpenAiToolOutcome
    data class Indeterminate(val reason: OpenAiToolIndeterminateReason) : OpenAiToolOutcome
}

enum class OpenAiToolRejectionReason {
    UNKNOWN_TOOL,
    DISABLED,
    UNAVAILABLE,
    INVALID_ARGUMENTS,
    POLICY_REFUSED,
    STALE_OPERATION,
}

enum class OpenAiToolFailureReason {
    HOST_FAILURE,
    TIMED_OUT,
    LIMIT_EXCEEDED,
}

enum class OpenAiToolIndeterminateReason {
    DELIVERY_UNCONFIRMED,
    CANCELLED_DURING_EFFECT,
    TIMED_OUT,
}

/** Ordered conversation messages; assistant tool calls and tool results retain their pairing. */
sealed interface OpenAiMessage {
    data class System(val text: String) : OpenAiMessage
    data class User(val text: String) : OpenAiMessage
    data class Assistant(
        val text: String?,
        val toolCalls: List<OpenAiToolCall> = emptyList(),
    ) : OpenAiMessage {
        init {
            require(!text.isNullOrEmpty() || toolCalls.isNotEmpty()) {
                "Assistant message must contain text or tool calls"
            }
        }
    }

    data class Tool(val result: OpenAiToolResult) : OpenAiMessage
}

data class OpenAiChatRequest(
    val profileId: OpenAiConnectionProfileId,
    val modelId: OpenAiModelId,
    val messages: List<OpenAiMessage>,
    val tools: List<OpenAiToolDefinition> = emptyList(),
    val parallelToolCalls: Boolean = false,
) {
    init {
        require(messages.isNotEmpty()) { "OpenAI chat request must contain at least one message" }
        require(tools.map { it.name }.distinct().size == tools.size) { "OpenAI tool names must be unique" }
    }
}

/** Normalized non-streaming completion result; no SDK response or transport failure escapes. */
sealed interface OpenAiChatOutcome {
    data class FinalAssistantMessage(val text: String) : OpenAiChatOutcome {
        init {
            require(text.isNotBlank()) { "Final assistant text must not be blank" }
        }
    }

    data class ToolCalls(val calls: List<OpenAiToolCall>) : OpenAiChatOutcome {
        init {
            require(calls.isNotEmpty()) { "Tool-call completion must contain at least one call" }
        }
    }

    data class Unavailable(val reason: OpenAiAvailabilityReason) : OpenAiChatOutcome
    data class Failed(val reason: OpenAiCompletionFailureReason) : OpenAiChatOutcome
    data object Cancelled : OpenAiChatOutcome
    data object Stale : OpenAiChatOutcome
}

enum class OpenAiCompletionFailureReason {
    AUTHENTICATION_FAILED,
    TIMED_OUT,
    INVALID_RESPONSE,
    REQUEST_TOO_LARGE,
    HOST_FAILURE,
}

/** Durable admission payload; the host allocates the run identity only after accepting it. */
data class AgentConversationEnqueueRequest(
    val profileId: OpenAiConnectionProfileId,
    val modelId: OpenAiModelId,
    val userText: String,
) {
    init {
        require(userText.isNotBlank()) { "Agent user turn must not be blank" }
    }
}

data class AcceptedAgentRun(
    val runId: AgentRunId,
    val sourceMessageId: AgentMessageId,
    val operationId: AgentOperationId,
)

/** Language-neutral projection of a durable asynchronous conversation run. */
data class AgentRun(
    val id: AgentRunId,
    val sourceMessageId: AgentMessageId,
    val profileId: OpenAiConnectionProfileId,
    val modelId: OpenAiModelId,
    val state: AgentRunState,
    val terminalOutcome: AgentRunTerminalOutcome? = null,
) {
    init {
        val terminalMatchesState = when (state) {
            AgentRunState.COMPLETED -> terminalOutcome is AgentRunTerminalOutcome.Completed
            AgentRunState.FAILED -> terminalOutcome is AgentRunTerminalOutcome.Failed
            AgentRunState.CANCELLED -> terminalOutcome is AgentRunTerminalOutcome.Cancelled
            AgentRunState.INDETERMINATE -> terminalOutcome is AgentRunTerminalOutcome.Indeterminate
            AgentRunState.QUEUED,
            AgentRunState.RUNNING,
            AgentRunState.WAITING_FOR_TOOL,
            AgentRunState.SYNTHESIZING,
            AgentRunState.PENDING_PLAYBACK,
            -> terminalOutcome == null
        }
        require(terminalMatchesState) { "Agent run state and terminal outcome must agree" }
    }
}

enum class AgentRunState {
    QUEUED,
    RUNNING,
    WAITING_FOR_TOOL,
    SYNTHESIZING,
    PENDING_PLAYBACK,
    COMPLETED,
    FAILED,
    CANCELLED,
    INDETERMINATE,
}

sealed interface AgentRunTerminalOutcome {
    data object Completed : AgentRunTerminalOutcome
    data class Failed(val reason: AgentRunFailureReason) : AgentRunTerminalOutcome
    data object Cancelled : AgentRunTerminalOutcome
    data class Indeterminate(val reason: AgentRunIndeterminateReason) : AgentRunTerminalOutcome
}

enum class AgentRunFailureReason {
    PROFILE_UNAVAILABLE,
    MODEL_UNAVAILABLE,
    TRANSCRIPTION_FAILED,
    COMPLETION_FAILED,
    TOOL_LOOP_LIMIT_EXCEEDED,
    SYNTHESIS_FAILED,
    PLAYBACK_FAILED,
    HOST_FAILURE,
}

enum class AgentRunIndeterminateReason {
    TOOL_EFFECT_UNCONFIRMED,
    REMOTE_EFFECT_UNCONFIRMED,
    PLAYBACK_EFFECT_UNCONFIRMED,
}

data class DelayedPlaybackRequest(
    val responseMessageId: AgentMessageId,
    val text: String,
) {
    init {
        require(text.isNotBlank()) { "Delayed playback text must not be blank" }
    }
}

sealed interface DelayedPlaybackOutcome {
    data class Pending(val operationId: DelayedPlaybackOperationId) : DelayedPlaybackOutcome
    data class Playing(val operationId: DelayedPlaybackOperationId) : DelayedPlaybackOutcome
    data class Heard(val operationId: DelayedPlaybackOperationId) : DelayedPlaybackOutcome
    data class Failed(val reason: DelayedPlaybackFailureReason) : DelayedPlaybackOutcome
    data object Cancelled : DelayedPlaybackOutcome
    data object Stale : DelayedPlaybackOutcome
    /**
     * The deferred queue is at capacity. Returned before the caller's audio
     * handle is consumed, before any partial entry is created, before any
     * sibling entry is evicted, and before any reroute. The caller retains
     * ownership of the artifact and MUST dispose it.
     */
    data object Busy : DelayedPlaybackOutcome
}

enum class DelayedPlaybackFailureReason {
    SYNTHESIS_FAILED,
    PLAYBACK_FAILED,
    HOST_FAILURE,
    TIMED_OUT,
}
