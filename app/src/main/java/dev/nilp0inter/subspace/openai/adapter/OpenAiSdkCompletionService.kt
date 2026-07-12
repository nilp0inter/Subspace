package dev.nilp0inter.subspace.openai.adapter

import com.openai.core.JsonValue
import com.openai.models.ChatModel
import com.openai.models.FunctionDefinition
import com.openai.models.FunctionParameters
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionFunctionTool
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall
import com.openai.models.chat.completions.ChatCompletionMessageToolCall
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionTool
import com.openai.models.chat.completions.ChatCompletionToolMessageParam
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import dev.nilp0inter.subspace.channel.capability.AgentOperationContext
import dev.nilp0inter.subspace.channel.capability.OpenAiCompletionCapability
import dev.nilp0inter.subspace.model.AgentToolCallId
import dev.nilp0inter.subspace.model.OpenAiChatOutcome
import dev.nilp0inter.subspace.model.OpenAiChatRequest
import dev.nilp0inter.subspace.model.OpenAiCompletionFailureReason
import dev.nilp0inter.subspace.model.OpenAiMessage
import dev.nilp0inter.subspace.model.OpenAiToolArgumentSchema
import dev.nilp0inter.subspace.model.OpenAiToolArgumentValue
import dev.nilp0inter.subspace.model.OpenAiToolCall
import dev.nilp0inter.subspace.model.OpenAiToolDefinition
import dev.nilp0inter.subspace.model.OpenAiToolName
import dev.nilp0inter.subspace.model.OpenAiToolOutcome
import dev.nilp0inter.subspace.model.OpenAiToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

/**
 * SDK-only completion adapter. It accepts only the host-normalized conversation representation
 * and maps SDK objects and failures back to normalized outcomes before they leave this package.
 */
internal class OpenAiSdkCompletionService(
    private val clients: OpenAiSdkClientRegistry,
) : OpenAiCompletionCapability {

    override suspend fun complete(context: AgentOperationContext, request: OpenAiChatRequest): OpenAiChatOutcome = try {
        withContext(Dispatchers.IO) {
            when (val execution = clients.execute(request.profileId) { client ->
                val params = ChatCompletionCreateParams.builder()
                    .model(ChatModel.of(request.modelId.value))
                    .messages(request.messages.map(::toSdkMessage))
                    .tools(request.tools.map(::toSdkTool))
                    .parallelToolCalls(false)
                    .build()
                client.chat().completions().create(params)
            }) {
                is OpenAiSdkClientRegistry.Execution.Current -> execution.value.toOutcome()
                is OpenAiSdkClientRegistry.Execution.Unavailable -> OpenAiChatOutcome.Unavailable(execution.reason)
                OpenAiSdkClientRegistry.Execution.Stale -> OpenAiChatOutcome.Stale
                OpenAiSdkClientRegistry.Execution.Cancelled -> OpenAiChatOutcome.Cancelled
                is OpenAiSdkClientRegistry.Execution.Failed -> OpenAiChatOutcome.Failed(execution.throwable.toFailureReason())
            }
        }
    } catch (_: CancellationException) {
        OpenAiChatOutcome.Cancelled
    }

    private fun com.openai.models.chat.completions.ChatCompletion.toOutcome(): OpenAiChatOutcome =
        runCatching {
            val message = choices().firstOrNull()?.message()
                ?: return OpenAiChatOutcome.Failed(OpenAiCompletionFailureReason.INVALID_RESPONSE)
            val calls = message.toolCalls().orElse(emptyList())
            if (calls.isNotEmpty()) {
                OpenAiChatOutcome.ToolCalls(calls.map(::fromSdkToolCall))
            } else {
                val text = message.content().orElse(null)?.trim()
                if (text.isNullOrEmpty()) OpenAiChatOutcome.Failed(OpenAiCompletionFailureReason.INVALID_RESPONSE)
                else OpenAiChatOutcome.FinalAssistantMessage(text)
            }
        }.getOrElse { OpenAiChatOutcome.Failed(OpenAiCompletionFailureReason.INVALID_RESPONSE) }

    private fun toSdkMessage(message: OpenAiMessage): ChatCompletionMessageParam = when (message) {
        is OpenAiMessage.System -> ChatCompletionMessageParam.ofSystem(
            ChatCompletionSystemMessageParam.builder().content(message.text).build(),
        )
        is OpenAiMessage.User -> ChatCompletionMessageParam.ofUser(
            ChatCompletionUserMessageParam.builder().content(message.text).build(),
        )
        is OpenAiMessage.Assistant -> ChatCompletionMessageParam.ofAssistant(
            ChatCompletionAssistantMessageParam.builder().apply {
                message.text?.let(::content)
                if (message.toolCalls.isNotEmpty()) toolCalls(message.toolCalls.map(::toSdkToolCall))
            }.build(),
        )
        is OpenAiMessage.Tool -> ChatCompletionMessageParam.ofTool(
            ChatCompletionToolMessageParam.builder()
                .content(message.result.toToolResultJson())
                .toolCallId(message.result.callId.value)
                .build(),
        )
    }

    private fun toSdkToolCall(call: OpenAiToolCall) = ChatCompletionMessageToolCall.ofFunction(
        ChatCompletionMessageFunctionToolCall.builder()
            .id(call.id.value)
            .function(
                ChatCompletionMessageFunctionToolCall.Function.builder()
                    .name(call.name.value)
                    .arguments(call.arguments.toJsonObject())
                    .build(),
            )
            .build(),
    )

    private fun fromSdkToolCall(call: ChatCompletionMessageToolCall): OpenAiToolCall {
        val function = call.asFunction().function()
        return OpenAiToolCall(
            id = AgentToolCallId(call.asFunction().id()),
            name = OpenAiToolName(function.name()),
            arguments = function.arguments().parseToolArguments(),
        )
    }

    private fun toSdkTool(definition: OpenAiToolDefinition): ChatCompletionTool = ChatCompletionTool.ofFunction(
        ChatCompletionFunctionTool.builder().function(
            FunctionDefinition.builder()
                .name(definition.name.value)
                .description(definition.description)
                .parameters(definition.arguments.toFunctionParameters())
                .strict(true)
                .build(),
        ).build(),
    )

    private fun OpenAiToolArgumentSchema.toFunctionParameters(): FunctionParameters =
        FunctionParameters.builder().putAdditionalProperty(
            "type",
            JsonValue.from("object"),
        ).putAdditionalProperty(
            "properties",
            JsonValue.from(properties.associate { property ->
                property.name to buildMap<String, Any> {
                    put("type", property.kind.jsonType)
                    if (property.description.isNotBlank()) put("description", property.description)
                }
            }),
        ).putAdditionalProperty(
            "required",
            JsonValue.from(properties.filter { it.required }.map { it.name }),
        ).putAdditionalProperty(
            "additionalProperties",
            JsonValue.from(additionalPropertiesAllowed),
        ).build()

    private val OpenAiToolArgumentSchema.Kind.jsonType: String
        get() = when (this) {
            OpenAiToolArgumentSchema.Kind.TEXT -> "string"
            OpenAiToolArgumentSchema.Kind.BOOLEAN -> "boolean"
            OpenAiToolArgumentSchema.Kind.INTEGER -> "integer"
            OpenAiToolArgumentSchema.Kind.NUMBER -> "number"
        }

    private fun String.parseToolArguments(): Map<String, OpenAiToolArgumentValue> {
        val parsed = JSONTokener(this).nextValue()
        require(parsed is JSONObject) { "Tool arguments must be a JSON object" }
        return buildMap {
            val keys = parsed.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                put(name, parsed.get(name).toToolArgumentValue())
            }
        }
    }

    private fun Any?.toToolArgumentValue(): OpenAiToolArgumentValue = when (this) {
        is String -> OpenAiToolArgumentValue.Text(this)
        is Boolean -> OpenAiToolArgumentValue.BooleanValue(this)
        is Byte, is Short, is Int, is Long -> OpenAiToolArgumentValue.IntegerValue((this as Number).toLong())
        is Float, is Double -> OpenAiToolArgumentValue.NumberValue((this as Number).toDouble().also { require(it.isFinite()) })
        else -> throw IllegalArgumentException("Tool argument values must be scalar JSON values")
    }

    private fun Map<String, OpenAiToolArgumentValue>.toJsonObject(): String = JSONObject().also { objectValue ->
        forEach { (name, value) -> objectValue.put(name, value.toJsonValue()) }
    }.toString()

    private fun OpenAiToolResult.toToolResultJson(): String = JSONObject().apply {
        put("outcome", outcome.jsonName())
        when (val result = outcome) {
            OpenAiToolOutcome.Delivered,
            OpenAiToolOutcome.Cancelled -> Unit
            is OpenAiToolOutcome.Rejected -> put("reason", result.reason.name)
            is OpenAiToolOutcome.Failed -> put("reason", result.reason.name)
            is OpenAiToolOutcome.Indeterminate -> put("reason", result.reason.name)
        }
        put("data", data.toJsonObject())
    }.toString()

    private fun OpenAiToolOutcome.jsonName(): String = when (this) {
        OpenAiToolOutcome.Delivered -> "DELIVERED"
        is OpenAiToolOutcome.Rejected -> "REJECTED"
        is OpenAiToolOutcome.Failed -> "FAILED"
        OpenAiToolOutcome.Cancelled -> "CANCELLED"
        is OpenAiToolOutcome.Indeterminate -> "INDETERMINATE"
    }

    private fun OpenAiToolArgumentValue.toJsonValue(): Any = when (this) {
        is OpenAiToolArgumentValue.Text -> value
        is OpenAiToolArgumentValue.BooleanValue -> value
        is OpenAiToolArgumentValue.IntegerValue -> value
        is OpenAiToolArgumentValue.NumberValue -> value
    }

    private fun Throwable.toFailureReason(): OpenAiCompletionFailureReason = when {
        this is SocketTimeoutException || this is TimeoutException || javaClass.simpleName.contains("Timeout", ignoreCase = true) -> OpenAiCompletionFailureReason.TIMED_OUT
        javaClass.simpleName in setOf("AuthenticationException", "PermissionDeniedException") -> OpenAiCompletionFailureReason.AUTHENTICATION_FAILED
        javaClass.simpleName in setOf("BadRequestException", "NotFoundException", "UnprocessableEntityException") -> OpenAiCompletionFailureReason.INVALID_RESPONSE
        javaClass.simpleName == "RequestTooLargeException" -> OpenAiCompletionFailureReason.REQUEST_TOO_LARGE
        else -> OpenAiCompletionFailureReason.HOST_FAILURE
    }
}
