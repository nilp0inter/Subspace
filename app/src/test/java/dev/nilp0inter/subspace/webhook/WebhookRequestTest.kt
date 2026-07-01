package dev.nilp0inter.subspace.webhook

import dev.nilp0inter.subspace.model.WebhookChannel
import dev.nilp0inter.subspace.model.WebhookHeader
import dev.nilp0inter.subspace.model.WebhookVerb
import org.junit.Assert.assertEquals
import org.junit.Test

class WebhookRequestTest {
    @Test
    fun rendersWebhookRequestFromChannelConfiguration() {
        val channel = WebhookChannel(
            url = " https://example.test/hook ",
            verb = WebhookVerb.PUT,
            headers = listOf(WebhookHeader("Authorization", "Bearer token")),
            bodyTemplate = "{\"text\":\"{{message}}\"}",
        )

        val request = renderWebhookRequest(channel, "hello road")

        assertEquals("https://example.test/hook", request.url)
        assertEquals(WebhookVerb.PUT, request.verb)
        assertEquals(listOf(WebhookHeader("Authorization", "Bearer token")), request.headers)
        assertEquals("{\"text\":\"hello road\"}", request.body)
    }

    @Test
    fun replacesEveryMessagePlaceholder() {
        val channel = WebhookChannel(
            url = "https://example.test/hook",
            bodyTemplate = "{{message}} / {{message}}",
        )

        val request = renderWebhookRequest(channel, "hello")

        assertEquals("hello / hello", request.body)
    }

    @Test
    fun escapesJsonStringContentInsideTemplate() {
        val channel = WebhookChannel(
            url = "https://example.test/hook",
            bodyTemplate = "{\"text\":\"{{message}}\"}",
        )

        val request = renderWebhookRequest(channel, "quote \" and newline\n")

        assertEquals("{\"text\":\"quote \\\" and newline\\n\"}", request.body)
    }
}
