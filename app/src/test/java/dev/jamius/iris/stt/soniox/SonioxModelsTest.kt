/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.stt.soniox

import dev.jamius.iris.stt.SttException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class SonioxModelsTest {

    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private suspend fun list(apiKey: String? = "sx-test", httpClient: OkHttpClient = OkHttpClient()) =
        listSonioxModels(httpClient, apiKey, server.url("/").toString())

    private fun respond(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun failure(block: suspend () -> Unit): SttException =
        assertThrows(SttException::class.java) { runBlocking { block() } }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun sendsGetWithBearerKey() = runBlocking {
        respond(200, """{"models":[]}""")

        list()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/models", request.target)
        assertEquals("Bearer sx-test", request.headers["Authorization"])
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun parsesModeAndStrictSortedById() = runBlocking {
        respond(
            200,
            """{"models":[
                {"id":"stt-rt-v6","aliased_model_id":null,"transcription_mode":"real_time","languages":[],
                 "supports_language_hints_strict":true,"supports_max_endpoint_delay":true},
                {"id":"stt-async-v6","transcription_mode":"async","supports_language_hints_strict":false}
            ]}"""
        )

        assertEquals(
            listOf(SonioxModel("stt-async-v6", "async", false), SonioxModel("stt-rt-v6", "real_time", true)),
            list()
        )
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun skipsEntriesWithoutStringIdAndMissingFlagIsFalse() {
        assertEquals(
            listOf(SonioxModel("a", null, false), SonioxModel("b", "async", false)),
            parseSonioxModels(
                """{"models":[{"id":"b","transcription_mode":"async","supports_language_hints_strict":"yes"},
                   {"id":7},{"transcription_mode":"async"},"x",{"id":"a"}]}"""
            )
        )
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun withoutKeyNothingIsRequested() {
        for (key in listOf(null, " ")) {
            assertEquals("Set your Soniox API key to load models", failure { list(apiKey = key) }.message)
        }
        assertEquals(0, server.requestCount)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun keyThatCannotBeAHeaderIsRejectedWithoutRequest() {
        for (key in listOf("sx-tést", "sx-test\n")) {
            assertEquals("Invalid Soniox API key", failure { list(apiKey = key) }.message)
        }
        assertEquals(0, server.requestCount)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun httpErrorsMapToReadableMessages() {
        val cases = listOf(
            401 to "Soniox rejected the API key. Check it in Iris settings.",
            402 to "Soniox account is out of credits or over budget",
            429 to "Soniox limit reached: Slow down",
            503 to "Soniox server error (503)",
            404 to "Soniox error: Slow down",
        )
        for ((code, message) in cases) {
            respond(code, """{"status_code":$code,"error_type":"x","message":"Slow down","validation_errors":[]}""")
            assertEquals(message, failure { list() }.message)
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun malformedBodyIsUnexpected() {
        for (body in listOf("not json", """{"object":"list"}""", """{"models":{}}""")) {
            respond(200, body)
            assertEquals("Unexpected response from Soniox", failure { list() }.message)
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun timeoutHasItsOwnMessage() {
        server.enqueue(MockResponse.Builder().body("""{"models":[]}""").headersDelay(2, TimeUnit.SECONDS).build())
        val client = OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build()

        assertEquals("Loading models timed out", failure { list(httpClient = client) }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun networkErrorIsShort() {
        val url = server.url("/").toString()
        server.close()

        val error = failure { listSonioxModels(OkHttpClient(), "sx-test", url) }
        assertEquals("Couldn't load models (network error)", error.message)
    }

    @Test
    fun catalogueEntryDecidesTheModeElseTheIdRule() {
        val catalog = listOf(
            SonioxModel("stt-rt-v6", "real_time", true),
            SonioxModel("stt-rt-legacy", "async", false),
            SonioxModel("custom-live", "real_time", false),
        )

        assertTrue(sonioxStreams("stt-rt-v6", catalog))
        assertFalse(sonioxStreams("stt-rt-legacy", catalog)) // The entry wins over the id.
        assertTrue(sonioxStreams("custom-live", catalog))
        assertTrue(sonioxStreams("stt-rt-v7", catalog)) // Not listed: the id rule.
        assertFalse(sonioxStreams("stt-async-v6", catalog))
        assertTrue(sonioxStreams("stt-rt-v6", null))
        assertFalse(sonioxStreams("stt-async-v6", null))
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 10_000L
    }
}
