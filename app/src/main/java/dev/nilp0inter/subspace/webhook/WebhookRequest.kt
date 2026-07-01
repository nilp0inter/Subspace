package dev.nilp0inter.subspace.webhook

import dev.nilp0inter.subspace.model.WebhookChannel
import dev.nilp0inter.subspace.model.WebhookHeader
import dev.nilp0inter.subspace.model.WebhookVerb

data class WebhookRequest(
    val url: String,
    val verb: WebhookVerb,
    val headers: List<WebhookHeader>,
    val body: String,
)

fun renderWebhookRequest(channel: WebhookChannel, message: String): WebhookRequest = WebhookRequest(
    url = channel.url.trim(),
    verb = channel.verb,
    headers = channel.headers,
    body = channel.bodyTemplate.replace(WebhookChannel.MESSAGE_PLACEHOLDER, message.escapeJsonStringContent()),
)

private fun String.escapeJsonStringContent(): String = buildString {
    this@escapeJsonStringContent.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 0x20) {
                append("\\u")
                append(char.code.toString(16).padStart(4, '0'))
            } else {
                append(char)
            }
        }
    }
}
