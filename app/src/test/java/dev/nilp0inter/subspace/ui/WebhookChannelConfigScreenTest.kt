package dev.nilp0inter.subspace.ui

import dev.nilp0inter.subspace.model.WebhookHeader
import org.junit.Assert.assertEquals
import org.junit.Test

class WebhookChannelConfigScreenTest {
    @Test
    fun formatsHeadersAsEditableLines() {
        val headers = listOf(
            WebhookHeader("Authorization", "Bearer token"),
            WebhookHeader("X-Demo", "subspace"),
        )

        val text = webhookHeadersText(headers)

        assertEquals("Authorization: Bearer token\nX-Demo: subspace", text)
    }

    @Test
    fun parsesEditableHeaderLines() {
        val text = "Authorization: Bearer token\nX-Demo: subspace"

        val headers = parseWebhookHeadersText(text)

        assertEquals(
            listOf(
                WebhookHeader("Authorization", "Bearer token"),
                WebhookHeader("X-Demo", "subspace"),
            ),
            headers,
        )
    }

    @Test
    fun parsesMissingSeparatorAsInvalidHeaderForReadiness() {
        val headers = parseWebhookHeadersText("Authorization")

        assertEquals(listOf(WebhookHeader("Authorization", "")), headers)
        assertEquals(false, headers.single().isValid)
    }
}
