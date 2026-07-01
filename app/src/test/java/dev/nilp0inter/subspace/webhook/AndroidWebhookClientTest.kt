package dev.nilp0inter.subspace.webhook

import dev.nilp0inter.subspace.model.WebhookHeader
import dev.nilp0inter.subspace.model.WebhookVerb
import java.net.ServerSocket
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidWebhookClientTest {
    @Test
    fun sendsConfiguredRequestAndMapsSuccessResponse() = runTest {
        val server = TestHttpServer(204)
        val request = WebhookRequest(
            url = server.url(),
            verb = WebhookVerb.POST,
            headers = listOf(WebhookHeader("X-Test", "yes")),
            body = "{\"text\":\"hello\"}",
        )

        val result = AndroidWebhookClient().send(request)

        assertEquals(WebhookDeliveryResult.Success(204), result)
        assertEquals(ReceivedRequest("POST", "yes", "{\"text\":\"hello\"}"), server.received())
    }

    @Test
    fun mapsNonSuccessResponseToFailure() = runTest {
        val server = TestHttpServer(500)
        val request = WebhookRequest(
            url = server.url(),
            verb = WebhookVerb.POST,
            headers = emptyList(),
            body = "{}",
        )

        val result = AndroidWebhookClient().send(request)

        assertEquals(WebhookDeliveryResult.Failure("HTTP 500"), result)
    }

    private data class ReceivedRequest(
        val method: String,
        val header: String,
        val body: String,
    )

    private class TestHttpServer(private val status: Int) {
        private val socket = ServerSocket(0)
        private lateinit var received: ReceivedRequest
        private val thread = Thread {
            socket.use { server ->
                val client = server.accept()
                client.use {
                    val input = client.getInputStream().bufferedReader()
                    val requestLine = input.readLine()
                    var contentLength = 0
                    var testHeader = ""
                    generateSequence { input.readLine() }
                        .takeWhile(String::isNotEmpty)
                        .forEach { line ->
                            val separator = line.indexOf(':')
                            if (separator > 0) {
                                val name = line.substring(0, separator)
                                val value = line.substring(separator + 1).trim()
                                when (name.lowercase()) {
                                    "content-length" -> contentLength = value.toInt()
                                    "x-test" -> testHeader = value
                                }
                            }
                        }
                    val body = CharArray(contentLength).also { input.read(it) }.concatToString()
                    received = ReceivedRequest(
                        method = requestLine.substringBefore(' '),
                        header = testHeader,
                        body = body,
                    )
                    val response = "HTTP/1.1 $status Test\r\nContent-Length: 0\r\n\r\n"
                    client.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        fun url(): String = "http://127.0.0.1:${socket.localPort}/hook"

        fun received(): ReceivedRequest {
            thread.join(1_000)
            return received
        }
    }
}
