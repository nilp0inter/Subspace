package dev.nilp0inter.subspace.ui

import dev.nilp0inter.subspace.model.BuiltInChannelDescriptors
import dev.nilp0inter.subspace.model.ChannelConfigurationField
import dev.nilp0inter.subspace.model.DynamicConfigurationChoiceUnavailableReason
import dev.nilp0inter.subspace.model.DynamicConfigurationChoiceResolution
import dev.nilp0inter.subspace.model.OpaqueJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelConfigurationUiHelpersTest {
    @Test
    fun `OpenAI edit preserves unavailable model selection and multiline prompt while updating declared values`() {
        val fields = BuiltInChannelDescriptors.openAiAgent.configurationFields
        val initial = OpaqueJsonObject.parse(
            """{
                "connectionProfileId":"old-profile",
                "modelId":"retired-model",
                "systemPrompt":"old prompt",
                "keyboardEnabled":false,
                "keyboardProfileId":null,
                "providerExtension":{"retained":true}
            }""",
        ).getOrThrow()

        val result = payloadWithFieldValues(
            initialPayload = initial,
            fields = fields,
            values = mapOf(
                "connectionProfileId" to "replacement-profile",
                "systemPrompt" to "Line one\nLine two\n\nLine four",
                "keyboardEnabled" to "true",
                "keyboardProfileId" to "keyboard-office",
            ),
        ).toJsonObject()

        assertEquals("replacement-profile", result.getString("connectionProfileId"))
        assertEquals("retired-model", result.getString("modelId"))
        assertEquals("Line one\nLine two\n\nLine four", result.getString("systemPrompt"))
        assertTrue(result.getBoolean("keyboardEnabled"))
        assertEquals("keyboard-office", result.getString("keyboardProfileId"))
        assertTrue(result.getJSONObject("providerExtension").getBoolean("retained"))
    }

    @Test
    fun `conditional dynamic choice visibility is driven only by its declared scalar dependency`() {
        val fields = BuiltInChannelDescriptors.openAiAgent.configurationFields
        val keyboardProfile = fields.filterIsInstance<ChannelConfigurationField.DynamicChoiceField>()
            .single { it.id == "keyboardProfileId" }
        val prompt = fields.filterIsInstance<ChannelConfigurationField.TextField>()
            .single { it.id == "systemPrompt" }

        assertFalse(keyboardProfile.isVisible(mapOf("keyboardEnabled" to "false")))
        assertTrue(keyboardProfile.isVisible(mapOf("keyboardEnabled" to "true")))
        assertTrue(prompt.isVisible(mapOf("keyboardEnabled" to "false")))
    }

    @Test
    fun `dynamic choice unavailability gives actionable messages without discarding a selected dependency`() {
        data class Case(
            val reason: DynamicConfigurationChoiceUnavailableReason,
            val dependency: String?,
            val expected: String,
        )
        val cases = listOf(
            Case(
                DynamicConfigurationChoiceUnavailableReason.DEPENDENCY_MISSING,
                null,
                "Choose the required dependency first.",
            ),
            Case(
                DynamicConfigurationChoiceUnavailableReason.DEPENDENCY_MISSING,
                "deleted-profile",
                "The selected dependency is unavailable.",
            ),
            Case(
                DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE,
                "profile",
                "Choices are currently unavailable.",
            ),
            Case(
                DynamicConfigurationChoiceUnavailableReason.DISCOVERY_FAILED,
                "profile",
                "Could not load choices. Refresh the source and try again.",
            ),
            Case(
                DynamicConfigurationChoiceUnavailableReason.HOST_NOT_READY,
                "profile",
                "Choices are unavailable until the host is ready.",
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.expected,
                dynamicChoiceUnavailableMessage(
                    DynamicConfigurationChoiceResolution.Unavailable(case.reason),
                    case.dependency,
                ),
            )
        }
    }
}
