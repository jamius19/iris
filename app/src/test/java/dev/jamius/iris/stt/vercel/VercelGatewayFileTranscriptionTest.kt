/*
* Author: Jamius Siam
* Since: 12/11/2026
*/

package dev.jamius.iris.stt.vercel

import dev.jamius.iris.stt.SttException
import dev.jamius.iris.stt.wavBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VercelGatewayFileTranscriptionTest {

    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun stt(
        apiKey: String? = "vck-test",
        httpClient: OkHttpClient = OkHttpClient(),
    ) = VercelGatewayFileTranscription(
        httpClient,
        apiKey = { apiKey },
        url = server.url("/v4/ai/transcription-model").toString(),
        warmUpUrl = server.url("/v1/models").toString()
    )

    private fun respond(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private suspend fun VercelGatewayFileTranscription.run(
        audio: Flow<ByteArray> = flowOf(CHUNK_A, CHUNK_B),
        model: String = MODEL,
        language: String = "",
        timeoutMs: Long = TIMEOUT_MS,
    ) = transcribe(audio, model, language, timeoutMs) { error("No live text") }

    private fun failure(block: suspend () -> Unit): SttException =
        assertThrows(SttException::class.java) { runBlocking { block() } }

    private fun requestJson(): JsonObject = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject

    @Test(timeout = TEST_TIMEOUT_MS)
    fun uploadsTheRecordingAsBase64WavAndReturnsTheText() = runBlocking {
        respond(200, """{"text":" Bonjour. ","segments":[],"language":"fr","durationInSeconds":1.25,"warnings":[]}""")

        val text = stt().run(model = "openai/gpt-transcribe", language = "fr")

        assertEquals("Bonjour.", text)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v4/ai/transcription-model", request.target)
        assertEquals("Bearer vck-test", request.headers["Authorization"])
        assertEquals("0.0.1", request.headers["ai-gateway-protocol-version"])
        assertEquals("api-key", request.headers["ai-gateway-auth-method"])
        assertEquals("4", request.headers["ai-transcription-model-specification-version"])
        assertEquals("openai/gpt-transcribe", request.headers["ai-model-id"])
        assertEquals("application/json", request.headers["Content-Type"]?.substringBefore(';'))
        val json = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        // 100 ms of audio: padded to 1.25 s.
        val wav = Base64.getDecoder().decode(json["audio"]!!.jsonPrimitive.content)
        assertArrayEquals(wavBytes(listOf(CHUNK_A, CHUNK_B), 16_000), wav)
        assertEquals(44 + 40_000, wav.size)
        assertEquals("audio/wav", json["mediaType"]!!.jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement("""{"openai":{"languages":["fr"]}}"""), json["providerOptions"])
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun noOptionsWithoutALanguage() = runBlocking {
        respond(200, """{"text":"Hi"}""")

        stt().run(model = "spacexai/grok-stt")

        assertFalse("providerOptions" in requestJson())
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun uploadsAfterStopAt16kHz() {
        val stt = stt()
        assertFalse(stt.streams)
        assertEquals(16_000, stt.sampleRate("openai/gpt-4o-transcribe"))
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun httpErrorsMapLikeCleanup() {
        respond(401, "")
        assertEquals("AI Gateway rejected the API key. Check it in Iris settings.", failure { stt().run() }.message)
        respond(429, "")
        assertEquals("AI Gateway rate limit or quota reached", failure { stt().run() }.message)
        respond(502, "")
        assertEquals("AI Gateway server error (HTTP 502)", failure { stt().run() }.message)
        respond(400, """{"error":{"message":"Model acme/stt not found","type":"model_not_found"}}""")
        assertEquals("AI Gateway error: Model acme/stt not found", failure { stt().run() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun responseWithoutTextIsUnexpected() {
        respond(200, """{"segments":[]}""")
        assertEquals("Unexpected response from AI Gateway", failure { stt().run() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun slowTranscriptTimesOut() {
        server.enqueue(MockResponse.Builder().body("""{"text":"late"}""").headersDelay(3, TimeUnit.SECONDS).build())

        val error = failure { stt().run(timeoutMs = 200) }

        assertEquals("Timed out waiting for the transcript", error.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun cancellingCancelsTheUpload() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"text":"late"}""").headersDelay(10, TimeUnit.SECONDS).build())
        val cancelled = CountDownLatch(1)
        val httpClient = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) = cancelled.countDown()
            })
            .build()

        val job = launch(Dispatchers.IO) { stt(httpClient = httpClient).run() }
        server.takeRequest() // The upload is in flight.
        job.cancelAndJoin()

        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun missingApiKeyFailsBeforeRecording() {
        var collected = false
        val audio = flow<ByteArray> { collected = true }

        val error = failure { stt(apiKey = null).run(audio = audio) }

        assertEquals("Add your Vercel AI Gateway API key in Iris settings", error.message)
        assertFalse(collected)
        assertEquals(0, server.requestCount)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun modelIdThatCantBeAHeaderFailsBeforeRecording() {
        var collected = false
        val audio = flow<ByteArray> { collected = true }

        val error = failure { stt().run(audio = audio, model = "openai/gpt-ü") }

        assertEquals("Invalid model id: openai/gpt-ü", error.message)
        assertFalse(collected)
        assertEquals(0, server.requestCount)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun warmUpIsAKeylessHeadThrottled() {
        server.enqueue(MockResponse.Builder().code(200).build())
        val stt = stt()

        stt.warmUp()
        stt.warmUp() // Within the window: skipped.
        val warmUp = server.takeRequest()

        assertEquals("HEAD", warmUp.method)
        assertEquals("/v1/models", warmUp.target)
        assertNull(warmUp.headers["Authorization"])
        assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS))
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 15_000L
        const val TIMEOUT_MS = 10_000L
        const val MODEL = "openai/gpt-4o-transcribe"

        /** 50 ms each at 16 kHz. */
        val CHUNK_A = ByteArray(1_600) { 1 }
        val CHUNK_B = ByteArray(1_600) { 2 }
    }
}
