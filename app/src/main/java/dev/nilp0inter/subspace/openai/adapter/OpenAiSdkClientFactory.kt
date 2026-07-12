package dev.nilp0inter.subspace.openai.adapter

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import java.time.Duration

/**
 * Sole construction boundary for the OpenAI Java SDK.
 *
 * SDK request, response, client, and exception types must remain in this package; host and
 * provider contracts exchange only Subspace-owned values.
 */
internal object OpenAiSdkClientFactory {
    fun create(
        bearerToken: String,
        baseUrl: String,
        timeout: Duration,
    ): OpenAIClient =
        OpenAIOkHttpClient.builder()
            .apiKey(bearerToken)
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .maxRetries(0)
            .timeout(timeout)
            .build()
}
