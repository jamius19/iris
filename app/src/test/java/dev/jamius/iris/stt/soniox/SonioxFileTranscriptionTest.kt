/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.stt.soniox

import dev.jamius.iris.stt.SttException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit

class SonioxFileTranscriptionTest {

    private val server = MockWebServer()

    /** Responses per "METHOD /path", in order; a DELETE without one gets 204, anything else 404. */
    private val responses = mutableMapOf<String, ArrayDeque<MockResponse>>()

    /** Every request the server got, in order. */
    private val recorded: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recorded += request
                val key = "${request.method} ${request.url.encodedPath}"
                return synchronized(responses) { responses[key]?.removeFirstOrNull() }
                    ?: MockResponse.Builder().code(if (request.method == "DELETE") 204 else 404).build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun on(method: String, path: String, vararg bodies: Pair<Int, String>) = synchronized(responses) {
        responses.getOrPut("$method $path") { ArrayDeque() } +=
            bodies.map { (code, body) -> MockResponse.Builder().code(code).body(body).build() }
    }

    private fun stt(apiKey: String? = "sx-test", modelInfo: SonioxModel? = null) = SonioxFileTranscription(
        OkHttpClient(),
        apiKey = { apiKey },
        modelInfo = { modelInfo },
        baseUrl = server.url("/").toString(),
        warmUpUrl = server.url("/v1/models").toString()
    )

    private suspend fun SonioxFileTranscription.run(
        audio: Flow<ByteArray> = flowOf(CHUNK),
        language: String = "",
        timeoutMs: Long = TIMEOUT_MS,
    ) = transcribe(audio, "stt-async-v6", language, timeoutMs) { error("No live text") }

    private fun failure(block: suspend () -> Unit): SttException =
        assertThrows(SttException::class.java) { runBlocking { block() } }

    /** Waits (up to 5 s) until the server got a [method] request for [path]. */
    private fun awaitRequest(method: String, path: String): RecordedRequest {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            synchronized(recorded) { recorded.firstOrNull { it.method == method && it.url.encodedPath == path } }
                ?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("No $method $path")
    }

    private fun paths(method: String) = synchronized(recorded) {
        recorded.filter { it.method == method }.map { it.url.encodedPath }
    }

    private fun job(status: String) = 200 to """{"id":"t1","status":"$status","model":"stt-async-v6"}"""

    /** Upload and create succeed. */
    private fun uploadAndCreate() {
        on("POST", "/v1/files", 201 to """{"id":"f1","filename":"audio.wav","size":40044}""")
        on("POST", "/v1/transcriptions", 201 to """{"id":"t1","status":"queued","file_id":"f1"}""")
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun uploadsCreatesPollsFetchesAndCleansUp() = runBlocking {
        uploadAndCreate()
        on("GET", "/v1/transcriptions/t1", job("queued"), job("processing"), job("completed"))
        on("GET", "/v1/transcriptions/t1/transcript", 200 to """{"id":"t1","text":" Hello there. ","tokens":[]}""")

        val text = stt(modelInfo = SonioxModel("stt-async-v6", "async", true)).run(language = "en")

        assertEquals("Hello there.", text)
        val upload = recorded[0]
        assertEquals("POST /v1/files", "${upload.method} ${upload.url.encodedPath}")
        assertEquals("Bearer sx-test", upload.headers["Authorization"])
        assertTrue(upload.headers["Content-Type"]!!.startsWith("multipart/form-data"))
        val form = String(upload.body!!.toByteArray(), Charsets.ISO_8859_1)
        assertTrue(form.contains("""name="file"; filename="audio.wav""""))
        assertTrue(form.contains("Content-Type: audio/wav"))
        assertTrue(form.contains("RIFF"))
        val create = recorded[1]
        assertEquals("POST /v1/transcriptions", "${create.method} ${create.url.encodedPath}")
        assertEquals("Bearer sx-test", create.headers["Authorization"])
        assertEquals(
            Json.parseToJsonElement(
                """{"model":"stt-async-v6","file_id":"f1","language_hints":["en"],"language_hints_strict":true}"""
            ),
            Json.parseToJsonElement(create.body!!.utf8())
        )
        assertEquals(
            List(3) { "/v1/transcriptions/t1" } + "/v1/transcriptions/t1/transcript",
            paths("GET")
        )
        awaitRequest("DELETE", "/v1/transcriptions/t1")
        assertEquals("Bearer sx-test", awaitRequest("DELETE", "/v1/files/f1").headers["Authorization"])
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun strictIsOnlySentWhenSupported() = runBlocking {
        for (info in listOf(null, SonioxModel("stt-async-v6", "async", false))) {
            recorded.clear()
            uploadAndCreate()
            on("GET", "/v1/transcriptions/t1", job("completed"))
            on("GET", "/v1/transcriptions/t1/transcript", 200 to """{"id":"t1","text":"Hi"}""")

            stt(modelInfo = info).run(language = "en")

            val create = awaitRequest("POST", "/v1/transcriptions")
            assertEquals(
                Json.parseToJsonElement("""{"model":"stt-async-v6","file_id":"f1","language_hints":["en"]}"""),
                Json.parseToJsonElement(create.body!!.utf8())
            )
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun failedJobFailsAndCleansUp() {
        for (status in listOf("error", "failed")) {
            recorded.clear()
            uploadAndCreate()
            on(
                "GET",
                "/v1/transcriptions/t1",
                200 to """{"id":"t1","status":"$status","error_type":"invalid_audio_file",
                    "error_message":"Bad audio"}"""
            )

            assertEquals("Soniox error: Bad audio", failure { stt().run() }.message)
            awaitRequest("DELETE", "/v1/transcriptions/t1")
            awaitRequest("DELETE", "/v1/files/f1")
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun uploadErrorMapsAndDeletesNothing() {
        on("POST", "/v1/files", 401 to """{"status_code":401,"error_type":"unauthenticated","message":"Bad key"}""")

        assertEquals("Soniox rejected the API key. Check it in Iris settings.", failure { stt().run() }.message)
        Thread.sleep(300)
        assertTrue(paths("DELETE").isEmpty())
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun createErrorDeletesTheFile() {
        on("POST", "/v1/files", 201 to """{"id":"f1"}""")
        on(
            "POST",
            "/v1/transcriptions",
            400 to """{"status_code":400,"error_type":"invalid_request","message":"",
                "validation_errors":[{"error_type":"invalid","location":"model","message":"Model not found"}]}"""
        )

        assertEquals("Soniox error: Model not found", failure { stt().run() }.message)
        awaitRequest("DELETE", "/v1/files/f1")
        Thread.sleep(200)
        assertEquals(listOf("/v1/files/f1"), paths("DELETE"))
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun pollDeadlineTimesOutAndCleansUp() {
        uploadAndCreate()
        on("GET", "/v1/transcriptions/t1", *Array(20) { job("processing") })

        // 300 ms setting + 50 ms of audio / 4: the poll deadline.
        assertEquals("Timed out waiting for the transcript", failure { stt().run(timeoutMs = 300) }.message)
        awaitRequest("DELETE", "/v1/transcriptions/t1")
        awaitRequest("DELETE", "/v1/files/f1")
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun cancelMidPollDeletesTheFile() = runBlocking {
        uploadAndCreate()
        on("GET", "/v1/transcriptions/t1", *Array(20) { job("processing") })

        val session = launch(Dispatchers.IO) { stt().run() }
        awaitRequest("GET", "/v1/transcriptions/t1")
        session.cancelAndJoin()

        awaitRequest("DELETE", "/v1/files/f1")
        awaitRequest("DELETE", "/v1/transcriptions/t1")
        Unit
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun malformedTranscriptIsUnexpected() {
        uploadAndCreate()
        on("GET", "/v1/transcriptions/t1", job("completed"))
        on("GET", "/v1/transcriptions/t1/transcript", 200 to """{"id":"t1","tokens":[]}""")

        assertEquals("Unexpected response from Soniox", failure { stt().run() }.message)
        awaitRequest("DELETE", "/v1/files/f1")
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun blankTranscriptIsReturnedBlank() = runBlocking {
        uploadAndCreate()
        on("GET", "/v1/transcriptions/t1", job("completed"))
        on("GET", "/v1/transcriptions/t1/transcript", 200 to """{"id":"t1","text":"  ","tokens":[]}""")

        // The controller turns a blank transcript into "No speech detected".
        assertEquals("", stt().run())
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun missingApiKeyFailsBeforeRecording() {
        var collected = false
        val audio = flow<ByteArray> { collected = true }

        val error = failure { stt(apiKey = null).run(audio = audio) }

        assertEquals("Add your Soniox API key in Iris settings", error.message)
        assertFalse(collected)
        assertEquals(0, server.requestCount)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun uploadsAfterStopAt16kHz() {
        val stt = stt()
        assertFalse(stt.streams)
        assertFalse(stt.liveText("stt-async-v6"))
        assertTrue(stt.allowsSpeedUp)
        assertEquals(16_000, stt.sampleRate("stt-async-v6"))
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun warmUpIsAKeylessHeadThrottled() {
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

        /** 50 ms at 16 kHz. */
        val CHUNK = ByteArray(1_600) { 1 }
    }
}
