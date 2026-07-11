package dev.nilp0inter.subspace.ui

import dev.nilp0inter.subspace.model.ChannelConfigurationField
import dev.nilp0inter.subspace.model.OpaqueJsonObject
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelConfigurationPayloadTest {
    private val fields = listOf(
        ChannelConfigurationField.BooleanField("enabled", "Enabled"),
        ChannelConfigurationField.TextField("label", "Label", required = false),
        ChannelConfigurationField.ChoiceField(
            "profile",
            "Profile",
            choices = listOf(
                ChannelConfigurationField.ChoiceField.Choice("alpha", "Alpha"),
                ChannelConfigurationField.ChoiceField.Choice("beta", "Beta"),
            ),
        ),
        ChannelConfigurationField.NumberField("retryLimit", "Retry limit", minimum = 0, maximum = 99),
        ChannelConfigurationField.DirectoryField("directory", "Directory", required = false),
    )

    @Test
    fun `native form payload converts every declared field type while preserving opaque provider fields`() {
        val initialPayload = opaque(
            """{
                "enabled":false,
                "label":"old label",
                "profile":"alpha",
                "retryLimit":3,
                "directory":"/old",
                "providerExtension":{"keep":true},
                "futureScalar":"untouched"
            }""",
        )

        val result = payloadWithFieldValues(
            initialPayload = initialPayload,
            fields = fields,
            values = mapOf(
                "enabled" to "true",
                "label" to "new label",
                "profile" to "beta",
                "retryLimit" to "42",
                "directory" to "/new",
            ),
        ).toJsonObject()

        assertTrue(result.getBoolean("enabled"))
        assertEquals("new label", result.getString("label"))
        assertEquals("beta", result.getString("profile"))
        assertEquals(42L, result.getLong("retryLimit"))
        assertEquals("/new", result.getString("directory"))
        assertTrue(result.getJSONObject("providerExtension").getBoolean("keep"))
        assertEquals("untouched", result.getString("futureScalar"))
    }

    @Test
    fun `form extraction preserves typed values and treats mismatched optional values as absent`() {
        val payload = JSONObject(
            """{
                "enabled":true,
                "label":"usable text",
                "profile":"beta",
                "retryLimit":7,
                "directory":null,
                "wrongBoolean":"true",
                "wrongNumber":"7"
            }""",
        )

        val byId = fields.associateBy(ChannelConfigurationField::id)
        assertEquals("true", initialFieldValue(byId.getValue("enabled"), payload))
        assertEquals("usable text", initialFieldValue(byId.getValue("label"), payload))
        assertEquals("beta", initialFieldValue(byId.getValue("profile"), payload))
        assertEquals("7", initialFieldValue(byId.getValue("retryLimit"), payload))
        assertEquals(null, initialFieldValue(byId.getValue("directory"), payload))
        assertEquals(
            null,
            initialFieldValue(ChannelConfigurationField.BooleanField("wrongBoolean", "Wrong"), payload),
        )
        assertEquals(
            null,
            initialFieldValue(ChannelConfigurationField.NumberField("wrongNumber", "Wrong"), payload),
        )
    }

    @Test
    fun `optional nulls and invalid numeric text remain explicit for provider validation`() {
        val result = payloadWithFieldValues(
            initialPayload = opaque("""{"enabled":true,"providerExtension":"preserve"}"""),
            fields = fields,
            values = mapOf(
                "label" to null,
                "profile" to null,
                "retryLimit" to "not-a-number",
                "directory" to null,
            ),
        ).toJsonObject()

        assertTrue(result.isNull("label"))
        assertTrue(result.isNull("profile"))
        assertEquals("not-a-number", result.getString("retryLimit"))
        assertTrue(result.isNull("directory"))
        assertTrue(result.getBoolean("enabled"))
        assertEquals("preserve", result.getString("providerExtension"))
    }

    @Test
    fun `directory selections retain both owner and field identity for host routing`() {
        val first = DirectorySelection("instance-one", "directory", "/first")
        val second = DirectorySelection("instance-two", "directory", "/second")
        val differentField = DirectorySelection("instance-one", "archiveDirectory", "/archive")

        assertFalse(first == second)
        assertFalse(first == differentField)
        assertEquals("instance-one", first.ownerId)
        assertEquals("directory", first.fieldId)
    }

    private fun opaque(encoded: String): OpaqueJsonObject = OpaqueJsonObject.parse(encoded).getOrThrow()
}
