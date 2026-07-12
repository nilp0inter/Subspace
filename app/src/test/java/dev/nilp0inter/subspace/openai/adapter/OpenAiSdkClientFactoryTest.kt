package dev.nilp0inter.subspace.openai.adapter

import com.openai.errors.OpenAIException
import com.openai.models.ChatModel
import com.openai.models.FunctionDefinition
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionFunctionTool
import com.openai.models.chat.completions.ChatCompletionTool
import java.time.Duration
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenAiSdkClientFactoryTest {
    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun customEndpointUsesBearerAuthAndCompletesNonStreamingArbitraryModelRequest() {
        server.start()
        server.enqueue(MockResponse().setBody(finalCompletion("compatible answer")))
        val client = OpenAiSdkClientFactory.create(
            bearerToken = "local-compatible-token",
            baseUrl = server.url("/compatible/v1/").toString(),
            timeout = Duration.ofSeconds(2),
        )
        try {
            val completion = client.chat().completions().create(
                ChatCompletionCreateParams.builder()
                    .model(ChatModel.of("gateway/model-2026.07"))
                    .addUserMessage("reply through the local compatible endpoint")
                    .build(),
            )
            val request = server.takeRequest(1, TimeUnit.SECONDS)
                ?: throw AssertionError("SDK did not send a Chat Completions request")
            val body = JSONObject(request.body.readUtf8())

            assertEquals("compatible answer", completion.choices().single().message().content().orElseThrow())
            assertEquals("/compatible/v1/chat/completions", request.path)
            assertEquals("Bearer local-compatible-token", request.getHeader("Authorization"))
            assertEquals("gateway/model-2026.07", body.getString("model"))
            assertFalse(body.has("stream"))
            assertEquals("user", body.getJSONArray("messages").getJSONObject(0).getString("role"))
        } finally {
            client.close()
        }
    }

    @Test
    fun toolDeclarationAndReturnedFunctionCallUseChatCompletionsWireShape() {
        server.start()
        server.enqueue(MockResponse().setBody(toolCallCompletion()))
        val client = OpenAiSdkClientFactory.create(
            bearerToken = "tool-compatible-token",
            baseUrl = server.url("/v1/").toString(),
            timeout = Duration.ofSeconds(2),
        )
        val tool = ChatCompletionTool.ofFunction(
            ChatCompletionFunctionTool.builder()
                .function(
                    FunctionDefinition.builder()
                        .name("type_text")
                        .description("Types text through the configured output profile")
                        .build(),
                )
                .build(),
        )
        try {
            val completion = client.chat().completions().create(
                ChatCompletionCreateParams.builder()
                    .model(ChatModel.of("compatible-tools-v1"))
                    .addUserMessage("type the selected text")
                    .addTool(tool)
                    .parallelToolCalls(false)
                    .build(),
            )
            val request = server.takeRequest(1, TimeUnit.SECONDS)
                ?: throw AssertionError("SDK did not send a tool-enabled Chat Completions request")
            val requestBody = JSONObject(request.body.readUtf8())
            val returnedCall = completion.choices().single().message().toolCalls().orElseThrow().single().asFunction()

            assertEquals("call_local_1", returnedCall.id())
            assertEquals("type_text", returnedCall.function().name())
            assertEquals("{\"text\":\"hello\"}", returnedCall.function().arguments())
            assertFalse(requestBody.getBoolean("parallel_tool_calls"))
            assertEquals(
                "type_text",
                requestBody.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun boundedClientTimeoutFailsTheLocalRequestInsteadOfWaitingForACompatibleServer() {
        server.start()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = OpenAiSdkClientFactory.create(
            bearerToken = "timeout-compatible-token",
            baseUrl = server.url("/v1/").toString(),
            timeout = Duration.ofMillis(150),
        )
        try {
            assertThrows(OpenAIException::class.java) {
                client.chat().completions().create(
                    ChatCompletionCreateParams.builder()
                        .model(ChatModel.of("timeout-compatible-model"))
                        .addUserMessage("this must use the configured deadline")
                        .build(),
                )
            }
        } finally {
            client.close()
        }
    }

    private fun finalCompletion(content: String): String =
        """
        {
          "id":"chatcmpl-local-final",
          "object":"chat.completion",
          "created":1710000000,
          "model":"compatible-model",
          "choices":[{
            "index":0,
            "message":{"role":"assistant","content":"$content","refusal":null},
            "finish_reason":"stop"
          }]
        }
        """.trimIndent()

    private fun toolCallCompletion(): String =
        """
        {
          "id":"chatcmpl-local-tool",
          "object":"chat.completion",
          "created":1710000001,
          "model":"compatible-tools-v1",
          "choices":[{
            "index":0,
            "message":{
              "role":"assistant",
              "content":null,
              "refusal":null,
              "tool_calls":[{
                "id":"call_local_1",
                "type":"function",
                "function":{"name":"type_text","arguments":"{\"text\":\"hello\"}"}
              }]
            },
            "finish_reason":"tool_calls"
          }]
        }
        """.trimIndent()
}
