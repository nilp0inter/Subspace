package dev.nilp0inter.subspace.model

import org.junit.Assert.assertThrows
import org.junit.Test

class OpenAiAgentContractsTest {
    @Test
    fun stableOpenAiAndAgentIdentifiersRejectBlankValues() {
        val invalidIdentifiers = listOf<() -> Unit>(
            { OpenAiConnectionProfileId(" ") },
            { OpenAiCredentialReference("\n") },
            { OpenAiModelId("") },
            { AgentMessageId("\t") },
            { AgentRunId(" ") },
            { AgentToolCallId("") },
            { AgentOperationId(" ") },
            { DelayedPlaybackOperationId("\n") },
            { OpenAiToolName("") },
        )

        invalidIdentifiers.forEach { construct ->
            assertThrows(IllegalArgumentException::class.java, construct)
        }
    }

    @Test
    fun chatValuesRejectAmbiguousAssistantTurnsAndDuplicateToolDeclarations() {
        val duplicateArguments = listOf(
            OpenAiToolArgumentSchema.Property(
                name = "text",
                kind = OpenAiToolArgumentSchema.Kind.TEXT,
                required = true,
            ),
            OpenAiToolArgumentSchema.Property(
                name = "text",
                kind = OpenAiToolArgumentSchema.Kind.TEXT,
                required = false,
            ),
        )
        val tool = OpenAiToolDefinition(
            name = OpenAiToolName("type_text"),
            description = "Types text through the configured output profile",
            arguments = OpenAiToolArgumentSchema(emptyList()),
        )

        assertThrows(IllegalArgumentException::class.java) {
            OpenAiMessage.Assistant(text = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiToolArgumentSchema(duplicateArguments)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiChatRequest(
                profileId = OpenAiConnectionProfileId("profile/compatible:gateway"),
                modelId = OpenAiModelId("gateway-model/2026.07"),
                messages = emptyList(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiChatRequest(
                profileId = OpenAiConnectionProfileId("profile/compatible:gateway"),
                modelId = OpenAiModelId("gateway-model/2026.07"),
                messages = listOf(OpenAiMessage.User("configure output")),
                tools = listOf(tool, tool),
            )
        }
    }

    @Test
    fun terminalCompletionValuesRequireAUsableSemanticResult() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiChatOutcome.FinalAssistantMessage(" \n ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiChatOutcome.ToolCalls(emptyList())
        }
    }
}
