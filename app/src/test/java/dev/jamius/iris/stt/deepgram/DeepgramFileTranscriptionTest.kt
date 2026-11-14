/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.stt.deepgram

import dev.jamius.iris.stt.SttException
import dev.jamius.iris.stt.wavBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DeepgramFileTranscriptionTest {

    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun stt(
        apiKey: String? = "dg-test",
        httpClient: OkHttpClient = OkHttpClient(),
    ) = DeepgramFileTranscription(
        httpClient,
        apiKey = { apiKey },
        url = server.url("/v1/listen").toString(),
        warmUpUrl = server.url("/v1/listen").toString()
    )

    private fun respond(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private suspend fun DeepgramFileTranscription.run(
        audio: Flow<ByteArray> = flowOf(CHUNK_A, CHUNK_B),
        language: String = "",
        timeoutMs: Long = TIMEOUT_MS,
    ) = transcribe(audio, "nova-3", language, timeoutMs) { error("No live text") }

    private fun failure(block: suspend () -> Unit): SttException =
        assertThrows(SttException::class.java) { runBlocking { block() } }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun uploadsTheRecordingAsWavAndReturnsTheTranscript() = runBlocking {
        respond(200, response(" Bonjour. "))

        val text = stt().run(language = "fr")

        assertEquals("Bonjour.", text)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/listen", request.url.encodedPath)
        assertEquals("Token dg-test", request.headers["Authorization"])
        assertEquals("audio/wav", request.headers["Content-Type"])
        assertEquals(
            mapOf("model" to "nova-3", "language" to "fr", "smart_format" to "true", "punctuate" to "true"),
            request.url.queryParameterNames.associateWith { request.url.queryParameter(it) }
        )
        // 100 ms of audio: padded to 1.25 s.
        val wav = request.body!!.toByteArray()
        assertArrayEquals(wavBytes(listOf(CHUNK_A, CHUNK_B), 16_000), wav)
        assertEquals(44 + 40_000, wav.size)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun blankLanguageIsMulti() = runBlocking {
        respond(200, response("Hola, hello."))

        stt().run()

        assertEquals("multi", server.takeRequest().url.queryParameter("language"))
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun blankTranscriptIsReturnedBlank() = runBlocking {
        respond(200, response(""))

        // The controller turns a blank transcript into "No speech detected".
        assertEquals("", stt().run())
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun uploadsAfterStopAt16kHz() {
        val stt = stt()
        assertFalse(stt.streams)
        assertFalse(stt.liveText("nova-3"))
        assertEquals(16_000, stt.sampleRate("nova-3"))
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun httpErrorsMapLikeTheStreamingHandshake() {
        respond(401, """{"err_code":"INVALID_AUTH","err_msg":"Invalid credentials."}""")
        assertEquals("Deepgram rejected the API key. Check it in Iris settings.", failure { stt().run() }.message)
        respond(402, "")
        assertEquals("Deepgram account is out of credits", failure { stt().run() }.message)
        respond(429, "")
        assertEquals("Deepgram rate limit reached", failure { stt().run() }.message)
        respond(504, "")
        assertEquals("Deepgram server error (HTTP 504)", failure { stt().run() }.message)
        respond(400, """{"err_code":"Bad Request","err_msg":"No such model/language/tier combination found."}""")
        assertEquals(
            "Deepgram error: No such model/language/tier combination found.",
            failure { stt().run() }.message
        )
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun responseWithoutTranscriptIsUnexpected() {
        respond(200, """{"metadata":{},"results":{"channels":[]}}""")
        assertEquals("Unexpected response from Deepgram", failure { stt().run() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun slowTranscriptTimesOut() {
        server.enqueue(MockResponse.Builder().body(response("late")).headersDelay(3, TimeUnit.SECONDS).build())

        val error = failure { stt().run(timeoutMs = 200) }

        assertEquals("Timed out waiting for the transcript", error.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun cancellingCancelsTheUpload() = runBlocking {
        server.enqueue(MockResponse.Builder().body(response("late")).headersDelay(10, TimeUnit.SECONDS).build())
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

        assertEquals("Add your Deepgram API key in Iris settings", error.message)
        assertFalse(collected)
        assertEquals(0, server.requestCount)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun warmUpIsAKeylessHeadThrottled() {
        server.enqueue(MockResponse.Builder().code(405).build())
        val stt = stt()

        stt.warmUp()
        stt.warmUp() // Within the window: skipped.
        val warmUp = server.takeRequest()

        assertEquals("HEAD", warmUp.method)
        assertEquals("/v1/listen", warmUp.target)
        assertNull(warmUp.headers["Authorization"])
        assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS))
    }

    private fun response(transcript: String) =
        """{"metadata":{"request_id":"r","duration":1.25,"channels":1},
           "results":{"channels":[{"alternatives":[{"transcript":"$transcript","confidence":0.9,"words":[]}]}]}}"""

    private companion object {
        const val TEST_TIMEOUT_MS = 15_000L
        const val TIMEOUT_MS = 10_000L

        /** 50 ms each at 16 kHz. */
        val CHUNK_A = ByteArray(1_600) { 1 }
        val CHUNK_B = ByteArray(1_600) { 2 }
    }
}
