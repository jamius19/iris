/*
* Author: Jamius Siam
* Since: 12/11/2026
*/

package dev.jamius.iris.stt.openai

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
import mockwebserver3.RecordedRequest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MultipartReader
import okhttp3.OkHttpClient
import okio.Buffer
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

class OpenAiFileTranscriptionTest {

    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun stt(
        apiKey: String? = "sk-test",
        httpClient: OkHttpClient = OkHttpClient(),
        maxPartBytes: Int = 20_000_000,
    ) = OpenAiFileTranscription(
        httpClient,
        apiKey = { apiKey },
        url = server.url("/v1/audio/transcriptions").toString(),
        warmUpUrl = server.url("/v1/models").toString(),
        maxPartBytes = maxPartBytes
    )

    private fun respond(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private suspend fun OpenAiFileTranscription.run(
        audio: Flow<ByteArray> = flowOf(CHUNK_A, CHUNK_B),
        model: String = "custom-transcribe",
        language: String = "",
        timeoutMs: Long = TIMEOUT_MS,
    ) = transcribe(audio, model, language, timeoutMs) { error("No live text") }

    private fun failure(block: suspend () -> Unit): SttException =
        assertThrows(SttException::class.java) { runBlocking { block() } }

    /** The multipart form fields: name to (file name, content type, bytes). */
    private fun parts(request: RecordedRequest): Map<String, Triple<String?, String?, ByteArray>> {
        val boundary = request.headers["Content-Type"]!!.substringAfter("boundary=")
        val reader = MultipartReader(Buffer().write(request.body!!), boundary)
        val parts = LinkedHashMap<String, Triple<String?, String?, ByteArray>>()
        while (true) {
            val part = reader.nextPart() ?: break
            val disposition = part.headers["Content-Disposition"]!!
            val name = disposition.substringAfter("name=\"").substringBefore('"')
            val fileName = disposition.substringAfter("filename=\"", "").substringBefore('"').ifEmpty { null }
            parts[name] = Triple(fileName, part.headers["Content-Type"], part.body.readByteArray())
        }
        return parts
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun uploadsTheRecordingAsWavAndReturnsTheText() = runBlocking {
        respond(200, """{"text":" Bonjour tout le monde. ","usage":{"type":"duration","seconds":2}}""")

        val text = stt().run(language = "fr")

        assertEquals("Bonjour tout le monde.", text)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/audio/transcriptions", request.target)
        assertEquals("Bearer sk-test", request.headers["Authorization"])
        val parts = parts(request)
        assertEquals(listOf("file", "model", "response_format", "language"), parts.keys.toList())
        val file = parts.getValue("file")
        assertEquals("audio.wav", file.first)
        assertEquals("audio/wav", file.second)
        assertArrayEquals(wavBytes(listOf(CHUNK_A, CHUNK_B), 16_000), file.third)
        assertEquals("custom-transcribe", String(parts.getValue("model").third))
        assertEquals("json", String(parts.getValue("response_format").third))
        assertEquals("fr", String(parts.getValue("language").third))
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun gptTranscribeTakesTheLanguagesList() = runBlocking {
        respond(200, """{"text":"Hi"}""")

        stt().run(model = "gpt-transcribe", language = "en")

        val parts = parts(server.takeRequest())
        assertEquals("en", String(parts.getValue("languages[]").third))
        assertFalse("language" in parts)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun blankLanguageSendsNone() = runBlocking {
        respond(200, """{"text":"Hi"}""")

        stt().run(language = " ")

        val parts = parts(server.takeRequest())
        assertEquals(listOf("file", "model", "response_format"), parts.keys.toList())
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun uploadsAfterStopAt16kHzWithoutLiveText() {
        val stt = stt()
        assertFalse(stt.streams)
        assertEquals(16_000, stt.sampleRate("custom-transcribe"))
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun overTheLimitUploadsPartsInOrderAndJoinsTheTexts() = runBlocking {
        respond(200, """{"text":"One."}""")
        respond(200, """{"text":"Two."}""")
        val chunks = List(4) { ByteArray(32_000) { 1 } } // 1 s each.

        val text = stt(maxPartBytes = 64_000).run(audio = flowOf(*chunks.toTypedArray()))

        assertEquals("One. Two.", text)
        assertEquals(2, server.requestCount)
        repeat(2) {
            val file = parts(server.takeRequest()).getValue("file").third
            assertArrayEquals(wavBytes(chunks.subList(2 * it, 2 * it + 2), 16_000), file)
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun missingApiKeyFailsBeforeRecording() {
        var collected = false
        val audio = flow<ByteArray> { collected = true }

        val error = failure { stt(apiKey = " ").run(audio = audio) }

        assertEquals("Add your OpenAI API key in Iris settings", error.message)
        assertFalse(collected)
        assertEquals(0, server.requestCount)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun httpErrorsMapToMessages() {
        respond(401, "")
        assertEquals("OpenAI rejected the API key. Check it in Iris settings.", failure { stt().run() }.message)
        respond(429, "")
        assertEquals("OpenAI rate limit or quota reached", failure { stt().run() }.message)
        respond(500, "")
        assertEquals("OpenAI server error (HTTP 500)", failure { stt().run() }.message)
        respond(400, """{"error":{"message":"Audio file is too short."}}""")
        assertEquals("OpenAI error: Audio file is too short.", failure { stt().run() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun responseWithoutTextIsUnexpected() {
        respond(200, """{"error":null}""")
        assertEquals("Unexpected response from OpenAI", failure { stt().run() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun slowTranscriptTimesOut() {
        // 1.25 s of padded audio: a read timeout of 200 ms + 312 ms.
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
    fun warmUpIsAKeylessHeadThrottled() {
        server.enqueue(MockResponse.Builder().code(401).build())
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

        /** 50 ms each at 16 kHz. */
        val CHUNK_A = ByteArray(1_600) { 1 }
        val CHUNK_B = ByteArray(1_600) { 2 }
    }
}
