/*
* Author: Jamius Siam
* Since: 12/11/2026
*/

package dev.jamius.iris.stt.openai

import dev.jamius.iris.stt.SttException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenAiModelsTest {

    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private suspend fun list(apiKey: String? = "sk-test", httpClient: OkHttpClient = OkHttpClient()) =
        listOpenAiTranscriptionModels(httpClient, apiKey, server.url("/v1/models").toString())

    private fun respond(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun failure(block: suspend () -> Unit): SttException =
        assertThrows(SttException::class.java) { runBlocking { block() } }

    private fun models(vararg ids: String): String =
        ids.joinToString(",", """{"object":"list","data":[""", "]}") {
            """{"id":"$it","object":"model","created":1686935002,"owned_by":"openai","shutdown_date":null}"""
        }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun sendsGetWithBearerKey() = runBlocking {
        respond(200, models())

        list()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/models", request.target)
        assertEquals("Bearer sk-test", request.headers["Authorization"])
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun keepsTranscriptionModelsSortedById() = runBlocking {
        respond(200, models("gpt-live-transcribe", "gpt-6-luna", "whisper-1", "GPT-4o-Transcribe", "tts-1"))

        assertEquals(listOf("GPT-4o-Transcribe", "gpt-live-transcribe", "whisper-1"), list())
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun skipsEntriesWithoutStringId() {
        assertEquals(
            listOf("whisper-1"),
            parseTranscriptionModelIds("""{"data":[{"id":"whisper-1"},{"id":7},{"object":"model"},"x"]}""")
        )
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun withoutKeyNothingIsRequested() {
        for (key in listOf(null, " ")) {
            assertEquals("Set your OpenAI API key to load models", failure { list(apiKey = key) }.message)
        }
        assertEquals(0, server.requestCount)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun keyThatCannotBeAHeaderIsRejectedWithoutRequest() {
        for (key in listOf("sk-tést", "sk-test\n")) {
            assertEquals("Invalid OpenAI API key", failure { list(apiKey = key) }.message)
        }
        assertEquals(0, server.requestCount)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun httpErrorsMapToReadableMessages() {
        val cases = listOf(
            401 to "OpenAI rejected the API key. Check it in Iris settings.",
            403 to "OpenAI rejected the API key. Check it in Iris settings.",
            429 to "OpenAI rate limit or quota reached",
            503 to "OpenAI server error (HTTP 503)",
            404 to "OpenAI error: Not found",
        )
        for ((code, message) in cases) {
            respond(code, """{"error":{"message":"Not found"}}""")
            assertEquals(message, failure { list() }.message)
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun malformedBodyIsUnexpected() {
        for (body in listOf("not json", """{"object":"list"}""", """{"data":{}}""")) {
            respond(200, body)
            assertEquals("Unexpected response from OpenAI", failure { list() }.message)
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun timeoutHasItsOwnMessage() {
        server.enqueue(MockResponse.Builder().body(models()).headersDelay(2, TimeUnit.SECONDS).build())
        val client = OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build()

        assertEquals("Loading models timed out", failure { list(httpClient = client) }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun networkErrorIsShort() {
        val url = server.url("/v1/models").toString()
        server.close()

        val error = failure { listOpenAiTranscriptionModels(OkHttpClient(), "sk-test", url) }
        assertEquals("Couldn't load models (network error)", error.message)
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 10_000L
    }
}
