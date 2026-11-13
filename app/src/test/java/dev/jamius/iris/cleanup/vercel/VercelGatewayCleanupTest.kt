/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.cleanup.vercel

import dev.jamius.iris.cleanup.CleanupException
import dev.jamius.iris.cleanup.GatewayModel
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
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        httpClient: OkHttpClient = OkHttpClient(),
    ) = VercelGatewayCleanup(
        httpClient,
        apiKey = { apiKey },
        url = server.url("/v1/chat/completions").toString(),
        modelsUrl = server.url("/v1/models").toString(),
        callTimeoutMs = callTimeoutMs
    )

    private suspend fun VercelGatewayCleanup.clean() = cleanup("hello", "Clean it.", "m", "low", null)

    private fun respond(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun respondCompletion(content: String) = respond(200, completion(content))

    /** A `chat.completion` whose `choices[0].message` has [content] and [refusal], and [finishReason]. */
    private fun completion(
        content: String? = null,
        refusal: String? = null,
        finishReason: String? = "stop",
    ) = buildJsonObject {
        put("id", "chatcmpl-1")
        put("object", "chat.completion")
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("index", 0)
                put("message", buildJsonObject {
                    put("role", "assistant")
                    put("content", content)
                    refusal?.let { put("refusal", it) }
                })
                put("finish_reason", finishReason)
            })
        })
    }.toString()

    private fun failure(block: suspend () -> Unit): CleanupException =
        assertThrows(CleanupException::class.java) { runBlocking { block() } }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun returnsTheParsedText() = runBlocking {
        respondCompletion("""{"text":"  Hello, world.  "}""")

        val text = cleanup().cleanup("um hello world", "Clean it.", "openai/gpt-6-luna", "low", null)

        assertEquals("Hello, world.", text)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun sendsStructuredRequest() = runBlocking {
        respondCompletion("""{"text":"Hi."}""")

        cleanup().cleanup("um hello world", "Clean it.", "openai/gpt-6-luna", "low", null)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/chat/completions", request.target)
        assertEquals("Bearer vck-test", request.headers["Authorization"])
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("openai/gpt-6-luna", body.string("model"))
        assertFalse("stream" in body)
        assertEquals("low", body.string("reasoning_effort"))
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
        assertFalse("providerOptions" in body)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun omitsReasoningEffortForModelDefault() = runBlocking {
        respondCompletion("""{"text":"Hi."}""")

        cleanup().cleanup("um hello world", "Clean it.", "openai/gpt-6-luna", null, null)

        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertFalse("reasoning_effort" in body)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun sendsProviderOptionsUnderProviderOptions() = runBlocking {
        respondCompletion("""{"text":"Hi."}""")
        val options = Json.parseToJsonElement("""{"gateway":{"order":["azure","openai"]}}""").jsonObject

        cleanup().cleanup("um hello world", "Clean it.", "openai/gpt-6-luna", "low", options)

        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals(options, body["providerOptions"])
        assertFalse("provider" in body)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun escapesDecode() = runBlocking {
        val content = """{"text":"Line\none \"q\" back\\slash caf\u00e9 \ud83d\ude00"}"""
        val expected = "Line\none \"q\" back\\slash café 😀"
        respondCompletion(content)

        assertEquals(expected, cleanup().clean())
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun contentThatIsNotTheSchemaFails() {
        respondCompletion("Hello, world.")

        assertEquals("Unexpected response from AI Gateway", failure { cleanup().clean() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun blankCleanedTextFails() {
        respondCompletion("""{"text":"  "}""")

        assertEquals("Unexpected response from AI Gateway", failure { cleanup().clean() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun malformedResponsesFail() {
        val bodies = listOf(
            completion(content = null),
            """{"id":"chatcmpl-1","choices":[]}""",
            "<html>oops</html>",
            "",
        )
        for (body in bodies) {
            respond(200, body)
            assertEquals(body, "Unexpected response from AI Gateway", failure { cleanup().clean() }.message)
        }
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
        server.enqueue(
            MockResponse.Builder().body(completion("""{"text":"late"}""")).headersDelay(2, TimeUnit.SECONDS).build()
        )

        assertEquals("Cleanup timed out", failure { cleanup(callTimeoutMs = 200).clean() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun cutOffOutputFails() {
        respond(200, completion("""{"text":"Hel""", finishReason = "length"))

        assertEquals("Cleanup output was cut off", failure { cleanup().clean() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun contentFilterFails() {
        respond(200, completion(finishReason = "content_filter"))

        assertEquals("Cleanup blocked by content filter", failure { cleanup().clean() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun refusalIsTheErrorMessage() {
        respond(200, completion(refusal = "I can't help with that."))

        assertEquals("Model refused: I can't help with that.", failure { cleanup().clean() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun cancellingBeforeTheResponseCancelsTheCall() = runBlocking {
        server.enqueue(
            MockResponse.Builder().body(completion("""{"text":"late"}""")).headersDelay(10, TimeUnit.SECONDS).build()
        )
        val cancelled = CountDownLatch(1)

        val job = launch(Dispatchers.IO) { cleanup(httpClient = cancelListener(cancelled)).clean() }
        server.takeRequest() // The call is in flight.
        job.cancelAndJoin()

        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
    }

    private fun cancelListener(cancelled: CountDownLatch) =
        OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) = cancelled.countDown()
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
    fun listModelsReturnsTheWholeCatalogSortedAndSendsNoKey() = runBlocking {
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

        val models = cleanup().listModels()

        assertEquals(
            listOf(
                "acme/untyped",
                "anthropic/claude-haiku",
                "bfl/flux-pro-1.1",
                "openai/gpt-6-luna",
                "openai/text-embedding-3-small"
            ),
            models.map { it.id }
        )
        assertEquals(listOf(null, "language", "image", "language", "embedding"), models.map { it.type })
        assertEquals(
            listOf("acme/untyped", "anthropic/claude-haiku", "openai/gpt-6-luna"),
            models.filter(::isChatModel).map { it.id }
        )
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/models", request.target)
        assertNull(request.headers["Authorization"])
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun listModelsWorksWithoutAKey() = runBlocking {
        respond(200, models("openai/gpt-6-luna" to "language"))

        assertEquals(listOf("openai/gpt-6-luna"), cleanup(apiKey = null).listModels().map { it.id })
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun listModelsParsesReasoningEfforts() = runBlocking {
        val body = """{"object":"list","data":[
            {"id":"a/luna","type":"language","reasoning_options":[
                {"type":"effort","values":["none","low","medium","high","xhigh","max"]}]},
            {"id":"b/gemini","type":"language","reasoning_options":[
                {"type":"toggle"},{"type":"effort","values":["low","high"]}]},
            {"id":"c/no-field","type":"language"},
            {"id":"d/null","type":"language","reasoning_options":null},
            {"id":"e/object","type":"language","reasoning_options":{}},
            {"id":"f/budget","type":"language","reasoning_options":[{"type":"budget_tokens","min":1024}]},
            {"id":"g/values-string","type":"language","reasoning_options":[{"type":"effort","values":"low"}]},
            {"id":"h/values-empty","type":"language","reasoning_options":[{"type":"effort","values":[]}]}
        ]}"""
        respond(200, body)

        val expected = listOf(
            "a/luna" to listOf("none", "low", "medium", "high", "xhigh", "max"),
            "b/gemini" to listOf("low", "high"),
            "c/no-field" to null,
            "d/null" to null,
            "e/object" to null,
            "f/budget" to null,
            "g/values-string" to null,
            "h/values-empty" to null,
        )
        assertEquals(expected, cleanup().listModels().map { it.id to it.reasoningEfforts })
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun listModelsParsesTypeAndTags() = runBlocking {
        respond(200, CATALOG)

        assertEquals(
            listOf(
                GatewayModel("elevenlabs/scribe-v2", "transcription", emptyList(), null),
                GatewayModel("openai/gpt-6-luna", "language", listOf("reasoning"), listOf("none", "low")),
                GatewayModel("openai/gpt-live-transcribe", "transcription", listOf("websocket-transcription"), null),
                GatewayModel(
                    "openai/gpt-realtime-whisper",
                    "transcription",
                    listOf("websocket-realtime", "websocket-transcription"),
                    null
                ),
                GatewayModel("openai/whisper-1", "transcription", listOf("open-weights"), null),
                GatewayModel("x/odd-tags", "transcription", listOf("websocket-transcription"), null),
            ),
            cleanup().listModels()
        )
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun chatModelFilterKeepsOnlyLanguageModels() = runBlocking {
        respond(200, CATALOG)

        assertEquals(listOf("openai/gpt-6-luna"), cleanup().listModels().filter(::isChatModel).map { it.id })
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
        respondCompletion("""{"text":"Hi."}""")
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
        respondCompletion("""{"text":"Hi."}""")

        assertEquals("Hi.", cleanup.clean())
    }

    private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content

    private companion object {
        /** A trimmed `/v1/models` snapshot. */
        const val CATALOG = """{"object":"list","data":[
            {"id":"openai/gpt-6-luna","type":"language","tags":["reasoning"],
             "reasoning_options":[{"type":"effort","values":["none","low"]}]},
            {"id":"openai/whisper-1","type":"transcription","tags":["open-weights"]},
            {"id":"openai/gpt-realtime-whisper","type":"transcription",
             "tags":["websocket-realtime","websocket-transcription"]},
            {"id":"openai/gpt-live-transcribe","type":"transcription","tags":["websocket-transcription"]},
            {"id":"elevenlabs/scribe-v2","type":"transcription"},
            {"id":"x/odd-tags","type":"transcription","tags":[1,"websocket-transcription",null]}
        ]}"""

        const val TEST_TIMEOUT_MS = 15_000L
    }
}
