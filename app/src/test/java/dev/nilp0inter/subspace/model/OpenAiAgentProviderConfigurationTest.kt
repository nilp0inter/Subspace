package dev.nilp0inter.subspace.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiAgentProviderConfigurationTest {
    @Test
    fun `v1 provider accepts both keyboard modes and rejects invalid cross field combinations`() {
        data class Case(
            val name: String,
            val payload: String,
            val accepted: Boolean,
        )
        val maximumPrompt = "é".repeat(8_192)
        val cases = listOf(
            Case(
                name = "keyboard tools disabled",
                payload = """{"connectionProfileId":"profile","modelId":"model","systemPrompt":"Reply in two lines.","keyboardEnabled":false}""",
                accepted = true,
            ),
            Case(
                name = "keyboard tools enabled with a profile",
                payload = """{"connectionProfileId":"profile","modelId":"model","systemPrompt":"Reply in two lines.","keyboardEnabled":true,"keyboardProfileId":"desktop"}""",
                accepted = true,
            ),
            Case(
                name = "blank connection profile",
                payload = """{"connectionProfileId":" ","modelId":"model","systemPrompt":"prompt","keyboardEnabled":false}""",
                accepted = false,
            ),
            Case(
                name = "blank model",
                payload = """{"connectionProfileId":"profile","modelId":"","systemPrompt":"prompt","keyboardEnabled":false}""",
                accepted = false,
            ),
            Case(
                name = "keyboard tools missing profile",
                payload = """{"connectionProfileId":"profile","modelId":"model","systemPrompt":"prompt","keyboardEnabled":true,"keyboardProfileId":" "}""",
                accepted = false,
            ),
            Case(
                name = "multibyte prompt at UTF-8 limit",
                payload = """{"connectionProfileId":"profile","modelId":"model","systemPrompt":"$maximumPrompt","keyboardEnabled":false}""",
                accepted = true,
            ),
            Case(
                name = "multibyte prompt over UTF-8 limit",
                payload = """{"connectionProfileId":"profile","modelId":"model","systemPrompt":"${maximumPrompt}a","keyboardEnabled":false}""",
                accepted = false,
            ),
        )

        cases.forEach { case ->
            val result = BuiltInChannelDescriptors.openAiAgent.configuration.migrateAndValidate(1, opaque(case.payload))

            if (case.accepted) {
                assertTrue("${case.name}: expected configuration to be usable", result is ProviderConfigurationResult.Success)
            } else {
                val failure = result as? ProviderConfigurationResult.Failure
                    ?: throw AssertionError("${case.name}: expected configuration rejection, got $result")
                assertTrue("${case.name}: expected invalid configuration, got ${failure.error}", failure.error is ChannelProviderError.InvalidConfiguration)
            }
        }
    }

    @Test
    fun `codec round trip retains enabled keyboard profile but clears a disabled stale reference`() {
        val enabled = OpenAiAgentProviderConfiguration(
            connectionProfileId = "profile/compatible",
            modelId = "vendor/custom-model",
            systemPrompt = "Preserve\nnewlines exactly.",
            keyboardEnabled = true,
            keyboardProfileId = "keyboard:office",
        )
        val disabledWithStaleProfile = enabled.copy(keyboardEnabled = false)

        val enabledPayload = OpenAiAgentProviderConfigurationCodec.encode(enabled)
        val disabledPayload = OpenAiAgentProviderConfigurationCodec.encode(disabledWithStaleProfile)

        assertEquals(enabled, OpenAiAgentProviderConfigurationCodec.decode(enabledPayload).getOrThrow())
        assertTrue(enabledPayload.toJsonObject().has("keyboardProfileId"))
        assertFalse(disabledPayload.toJsonObject().has("keyboardProfileId"))
        assertEquals(
            null,
            OpenAiAgentProviderConfigurationCodec.decode(disabledPayload).getOrThrow().keyboardProfileId,
        )
    }

    private fun opaque(value: String): OpaqueJsonObject = OpaqueJsonObject.parse(value).getOrThrow()
}
