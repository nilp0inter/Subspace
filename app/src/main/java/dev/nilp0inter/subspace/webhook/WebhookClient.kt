package dev.nilp0inter.subspace.webhook

import dev.nilp0inter.subspace.model.WebhookVerb
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface WebhookClient {
    suspend fun send(request: WebhookRequest): WebhookDeliveryResult
}

sealed interface WebhookDeliveryResult {
    data class Success(val responseCode: Int) : WebhookDeliveryResult
    data class Failure(val reason: String) : WebhookDeliveryResult
}

class AndroidWebhookClient(
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) : WebhookClient {
    override suspend fun send(request: WebhookRequest): WebhookDeliveryResult = withContext(Dispatchers.IO) {
        runCatching { sendBlocking(request) }
            .getOrElse { error -> WebhookDeliveryResult.Failure(error.message ?: "Webhook request failed") }
    }

    private fun sendBlocking(request: WebhookRequest): WebhookDeliveryResult {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = request.verb.name
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doInput = true
            request.headers.forEach { header -> setRequestProperty(header.name, header.value) }
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        return try {
            if (request.verb.allowsRequestBody()) {
                connection.doOutput = true
                connection.outputStream.use { output -> output.write(request.body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code in 200..299) {
                WebhookDeliveryResult.Success(code)
            } else {
                WebhookDeliveryResult.Failure("HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000
    }
}

private fun WebhookVerb.allowsRequestBody(): Boolean = when (this) {
    WebhookVerb.POST, WebhookVerb.PUT, WebhookVerb.PATCH -> true
    WebhookVerb.GET, WebhookVerb.DELETE -> false
}
