/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.cleanup.vercel

import dev.jamius.iris.cleanup.CleanupException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.BufferedSink
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VercelGatewayCleanupTest {

    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun cleanup(
        apiKey: String? = "vck-test",
        callTimeoutMs: Long = 10_000,
        stallTimeoutMs: Long = 10_000,
        httpClient: OkHttpClient = OkHttpClient(),
    ) = VercelGatewayCleanup(
        httpClient,
        apiKey = { apiKey },
        url = server.url("/v1/chat/completions").toString(),
        modelsUrl = server.url("/v1/models").toString(),
        callTimeoutMs = callTimeoutMs,
        stallTimeoutMs = stallTimeoutMs
    )

    private suspend fun VercelGatewayCleanup.clean() = cleanup("hello", "Clean it.", "m")

    private fun respond(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun respondStream(body: String) = server.enqueue(streamResponse(body).build())

    private fun streamResponse(body: String) =
        MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(body)

    /** An SSE body with one `data:` event per payload. */
    private fun sse(vararg payloads: String) = payloads.joinToString("") { "data: $it\n\n" }

    /** A `chat.completion.chunk` whose `choices[0].delta` has [content] and/or [refusal], and [finishReason]. */
    private fun chunk(
        content: String? = null,
        refusal: String? = null,
        finishReason: String? = null,
    ) = buildJsonObject {
        put("id", "chatcmpl-1")
        put("object", "chat.completion.chunk")
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("index", 0)
                put("delta", buildJsonObject {
                    put("role", "assistant")
                    content?.let { put("content", it) }
                    refusal?.let { put("refusal", it) }
                })
                put("finish_reason", finishReason)
            })
        })
    }.toString()

    /** A complete stream whose content deltas are [contents]. */
    private fun streamed(vararg contents: String) = sse(*contents.map { chunk(it) }.toTypedArray(), "[DONE]")

    private fun failure(block: suspend () -> Unit): CleanupException =
        assertThrows(CleanupException::class.java) { runBlocking { block() } }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun joinsStreamedContentAndReturnsTheParsedText() = runBlocking {
        val body = sse(chunk(content = "")) + ": heartbeat\n\n" +
            sse(
                chunk("""{"te"""),
                chunk("""xt":"  Hel"""),
                chunk("""lo, wo"""),
                chunk("""rld.  "}"""),
                """{"id":"chatcmpl-1","choices":[],"usage":{"total_tokens":9}}""",
                "[DONE]"
            )
        respondStream(body)

        val text = cleanup().cleanup("um hello world", "Clean it.", "openai/gpt-6-luna")

        assertEquals("Hello, world.", text)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun sendsStreamingStructuredRequest() = runBlocking {
        respondStream(streamed("""{"text":"Hi."}"""))

        cleanup().cleanup("um hello world", "Clean it.", "openai/gpt-6-luna")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/chat/completions", request.target)
        assertEquals("Bearer vck-test", request.headers["Authorization"])
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("openai/gpt-6-luna", body.string("model"))
        assertEquals("true", body.string("stream"))
        assertEquals("none", body.string("reasoning_effort"))
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user"), messages.map { it.string("role") })
        assertEquals("Clean it.\n\n$TRANSCRIPT_FRAMING", messages[0].string("content"))
        assertEquals("um hello world", messages[1].string("content"))
        val expectedFormat = Json.parseToJsonElement(
            """{"type":"json_schema","json_schema":{"name":"cleaned_text","strict":true,"schema":{
               "type":"object","properties":{"text":{"type":"string"}},"required":["text"],
               "additionalProperties":false}}}"""
        )
        assertEquals(expectedFormat, body["response_format"])
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun escapesSplitAcrossChunksDecode() = runBlocking {
        val content = """{"text":"Line\none \"q\" back\\slash caf\u00e9 \ud83d\ude00"}"""
        val expected = "Line\none \"q\" back\\slash café 😀"
        // One delta per character splits every escape.
        respondStream(streamed(*content.map { it.toString() }.toTypedArray()))

        assertEquals(expected, cleanup().clean())
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun contentThatIsNotTheSchemaFails() {
        respondStream(streamed("Hello, ", "world."))

        assertEquals("Unexpected response from AI Gateway", failure { cleanup().clean() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun blankCleanedTextFails() {
        respondStream(streamed("""{"text":"  "}"""))

        assertEquals("Unexpected response from AI Gateway", failure { cleanup().clean() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun truncatedStreamFails() {
        respondStream(sse(chunk("""{"text":"Hel"""), chunk("lo")))

        assertEquals("Unexpected response from AI Gateway", failure { cleanup().clean() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun malformedStreamsFail() {
        val bodies = listOf(
            sse(chunk("""{"text":"""), "not json", "[DONE]"),
            sse("""{"id":"chatcmpl-1","choices":[]}""", "[DONE]"),
            "<html>oops</html>",
            "",
        )
        for (body in bodies) {
            respondStream(body)
            assertEquals(body, "Unexpected response from AI Gateway", failure { cleanup().clean() }.message)
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun errorEventMidStreamFails() {
        val error = """{"error":{"message":"Stream error occurred","type":"server_error"}}"""
        respondStream(sse(chunk("""{"text":"Hel"""), error, "[DONE]"))
        assertEquals("AI Gateway error: Stream error occurred", failure { cleanup().clean() }.message)

        respondStream(sse(chunk("""{"text":"Hel"""), """{"error":"boom"}"""))
        assertEquals("AI Gateway error", failure { cleanup().clean() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun httpErrorsMapToReadableMessages() {
        val cases = listOf(
            401 to "AI Gateway rejected the API key. Check it in Iris settings.",
            403 to "AI Gateway rejected the API key. Check it in Iris settings.",
            429 to "AI Gateway rate limit or quota reached",
            503 to "AI Gateway server error (HTTP 503)",
        )
        for ((code, message) in cases) {
            respond(code, """{"error":{"message":"ignored"}}""")
            assertEquals(message, failure { cleanup().clean() }.message)
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun clientErrorShowsGatewayMessageWhenPresent() {
        respond(400, """{"error":{"message":"Model not found","type":"invalid_request_error"}}""")
        assertEquals("AI Gateway error: Model not found", failure { cleanup().clean() }.message)

        respond(404, "not json")
        assertEquals("AI Gateway request failed (HTTP 404)", failure { cleanup().clean() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun slowResponseTimesOut() {
        server.enqueue(streamResponse(streamed("""{"text":"late"}""")).headersDelay(2, TimeUnit.SECONDS).build())

        assertEquals("Cleanup timed out", failure { cleanup(stallTimeoutMs = 200).clean() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun streamOfOnlyHeartbeatsTimesOut() {
        val parts = listOf(sse(chunk("""{"text":"Hi"""))) + List(20) { ": heartbeat\n\n" }
        // Heartbeats every 100 ms keep the socket busy (no read timeout) for 2 s.
        server.enqueue(streamResponse("").body(paced(parts, delayMs = 100)).build())

        assertEquals("Cleanup timed out", failure { cleanup(stallTimeoutMs = 300).clean() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun streamThatKeepsProgressingOutlivesTheStallTimeout() = runBlocking {
        val parts = listOf(sse(chunk("""{"text":""""))) + List(10) { sse(chunk("word ")) } + streamed(""""}""")
        // An event every 100 ms: 1.1 s in total.
        server.enqueue(streamResponse("").body(paced(parts, delayMs = 100)).build())

        assertEquals("word ".repeat(10).trim(), cleanup(stallTimeoutMs = 500).clean())
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun cutOffOutputFails() {
        respondStream(sse(chunk("""{"text":"Hel"""), chunk("lo", finishReason = "length"), "[DONE]"))

        assertEquals("Cleanup output was cut off", failure { cleanup().clean() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun contentFilterFails() {
        respondStream(sse(chunk(finishReason = "content_filter"), "[DONE]"))

        assertEquals("Cleanup blocked by content filter", failure { cleanup().clean() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun refusalIsTheErrorMessage() {
        respondStream(sse(chunk(refusal = "I can't "), chunk(refusal = "help with that."), "[DONE]"))

        assertEquals("Model refused: I can't help with that.", failure { cleanup().clean() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun cancellingBeforeTheResponseCancelsTheCall() = runBlocking {
        server.enqueue(streamResponse(streamed("""{"text":"late"}""")).headersDelay(10, TimeUnit.SECONDS).build())
        val cancelled = CountDownLatch(1)

        val job = launch(Dispatchers.IO) { cleanup(httpClient = cancelListener(cancelled)).clean() }
        server.takeRequest() // The call is in flight.
        job.cancelAndJoin()

        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun cancellingMidStreamCancelsTheCall() = runBlocking {
        val release = CountDownLatch(1)
        // The first event at once, the rest only when released.
        val parts = listOf(sse(chunk("""{"text":"Hi""")), streamed(" there", """."}"""))
        server.enqueue(streamResponse("").body(paced(parts, release = release)).build())
        val cancelled = CountDownLatch(1)
        val headersReceived = CountDownLatch(1)

        val job = launch(Dispatchers.IO) { cleanup(httpClient = cancelListener(cancelled, headersReceived)).clean() }
        assertTrue(headersReceived.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()

        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        release.countDown()
    }

    /** A body written in [parts], each flushed; before each after the first, waits [delayMs] or for [release]. */
    private fun paced(parts: List<String>, delayMs: Long = 0, release: CountDownLatch? = null) =
        object : MockResponseBody {
            override val contentLength = parts.sumOf { it.encodeUtf8().size }.toLong()

            override fun writeTo(sink: BufferedSink) {
                parts.forEachIndexed { i, part ->
                    if (i > 0) release?.await(10, TimeUnit.SECONDS) ?: Thread.sleep(delayMs)
                    sink.writeUtf8(part)
                    sink.flush()
                }
            }
        }

    private fun cancelListener(cancelled: CountDownLatch, headersReceived: CountDownLatch? = null) =
        OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) = cancelled.countDown()

                override fun responseHeadersEnd(call: Call, response: Response) {
                    headersReceived?.countDown()
                }
            })
            .build()

    @Test(timeout = TEST_TIMEOUT_MS)
    fun missingApiKeyFailsWithoutCalling() {
        val error = failure { cleanup(apiKey = null).clean() }

        assertEquals("Add your Vercel AI Gateway API key in Iris settings", error.message)
        assertEquals(0, server.requestCount)
    }

    /** A `GET /v1/models` body; each pair is an id and its `type` (omitted when null). */
    private fun models(vararg models: Pair<String, String?>) = buildJsonObject {
        put("object", "list")
        put("data", buildJsonArray {
            for ((id, type) in models) {
                add(buildJsonObject {
                    put("id", id)
                    put("object", "model")
                    type?.let { put("type", it) }
                })
            }
        })
    }.toString()

    @Test(timeout = TEST_TIMEOUT_MS)
    fun listModelsKeepsLanguageModelsSortedAndSendsNoKey() = runBlocking {
        respond(
            200,
            models(
                "openai/gpt-6-luna" to "language",
                "openai/text-embedding-3-small" to "embedding",
                "anthropic/claude-haiku" to "language",
                "bfl/flux-pro-1.1" to "image",
                "acme/untyped" to null,
            )
        )

        val ids = cleanup().listModels()

        assertEquals(listOf("acme/untyped", "anthropic/claude-haiku", "openai/gpt-6-luna"), ids)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/models", request.target)
        assertNull(request.headers["Authorization"])
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun listModelsWorksWithoutAKey() = runBlocking {
        respond(200, models("openai/gpt-6-luna" to "language"))

        assertEquals(listOf("openai/gpt-6-luna"), cleanup(apiKey = null).listModels())
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun listModelsMalformedResponseFails() {
        for (body in listOf("<html>oops</html>", """{"object":"list"}""", """{"data":{}}""")) {
            respond(200, body)
            assertEquals("Unexpected response from AI Gateway", failure { cleanup().listModels() }.message)
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun listModelsHttpErrorsMapToReadableMessages() {
        val cases = listOf(
            401 to "AI Gateway rejected the API key. Check it in Iris settings.",
            429 to "AI Gateway rate limit or quota reached",
            502 to "AI Gateway server error (HTTP 502)",
            404 to "AI Gateway request failed (HTTP 404)",
        )
        for ((code, message) in cases) {
            respond(code, "not json")
            assertEquals(message, failure { cleanup().listModels() }.message)
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun listModelsTimeoutHasItsOwnMessage() {
        server.enqueue(
            MockResponse.Builder()
                .body(models("openai/gpt-6-luna" to "language"))
                .headersDelay(2, TimeUnit.SECONDS)
                .build()
        )

        val error = failure { cleanup(callTimeoutMs = 200).listModels() }
        assertEquals("Loading models timed out", error.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun warmUpSendsHeadOfModelsWithoutKey() {
        respond(200, "")

        cleanup().warmUp()

        val request = server.takeRequest()
        assertEquals("HEAD", request.method)
        assertEquals("/v1/models", request.target)
        assertNull(request.headers["Authorization"])
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun cleanupReusesTheWarmedUpConnection() = runBlocking {
        respond(200, "")
        respondStream(streamed("""{"text":"Hi."}"""))
        val httpClient = OkHttpClient()
        val cleanup = cleanup(httpClient = httpClient)

        cleanup.warmUp()
        val warmUp = server.takeRequest()
        while (httpClient.connectionPool.idleConnectionCount() == 0) Thread.sleep(10) // Response consumed.
        cleanup.clean()

        val request = server.takeRequest()
        assertEquals(warmUp.connectionIndex, request.connectionIndex)
        assertEquals(1, request.exchangeIndex) // Second exchange on that connection.
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun warmUpFailureIsIgnored() = runBlocking {
        val httpClient = OkHttpClient()
        val closed = MockWebServer().apply { start() }
        val closedUrl = closed.url("/v1/models").toString()
        closed.close() // Connection refused.
        val cleanup = VercelGatewayCleanup(
            httpClient,
            apiKey = { "vck-test" },
            url = server.url("/v1/chat/completions").toString(),
            modelsUrl = closedUrl,
            callTimeoutMs = 10_000
        )

        cleanup.warmUp()
        while (httpClient.dispatcher.runningCallsCount() > 0) Thread.sleep(10)
        respondStream(streamed("""{"text":"Hi."}"""))

        assertEquals("Hi.", cleanup.clean())
    }

    private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content

    private companion object {
        const val TEST_TIMEOUT_MS = 15_000L
    }
}
