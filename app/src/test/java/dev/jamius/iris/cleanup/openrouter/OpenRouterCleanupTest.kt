/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.cleanup.openrouter

import dev.jamius.iris.cleanup.CleanupException
import dev.jamius.iris.cleanup.GatewayModel
import dev.jamius.iris.cleanup.vercel.TRANSCRIPT_FRAMING
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

class OpenRouterCleanupTest {

    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun cleanup(
        apiKey: String? = "sk-or-test",
        callTimeoutMs: Long = 10_000,
        httpClient: OkHttpClient = OkHttpClient(),
        modelsUrl: String = server.url("/api/v1/models").toString(),
    ) = OpenRouterCleanup(
        httpClient,
        apiKey = { apiKey },
        url = server.url("/api/v1/chat/completions").toString(),
        modelsUrl = modelsUrl,
        callTimeoutMs = callTimeoutMs
    )

    private suspend fun OpenRouterCleanup.clean(options: JsonObject? = null) =
        cleanup("hello", "Clean it.", "m", "low", options)

    private fun respond(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun respondCompletion(content: String) = respond(200, completion(content))

    /** A `chat.completion` whose `choices[0].message` has [content] and [refusal], and [finishReason]. */
    private fun completion(
        content: String? = null,
        refusal: String? = null,
        finishReason: String? = "stop",
    ) = buildJsonObject {
        put("id", "gen-1")
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

    private fun requestBody() = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject

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
        assertEquals("/api/v1/chat/completions", request.target)
        assertEquals("Bearer sk-or-test", request.headers["Authorization"])
        assertNull(request.headers["HTTP-Referer"])
        assertNull(request.headers["X-OpenRouter-Title"])
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
        assertFalse("provider" in body)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun omitsReasoningEffortForModelDefault() = runBlocking {
        respondCompletion("""{"text":"Hi."}""")

        cleanup().cleanup("um hello world", "Clean it.", "openai/gpt-6-luna", null, null)

        assertFalse("reasoning_effort" in requestBody())
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun sendsProviderOptionsUnderProvider() = runBlocking {
        respondCompletion("""{"text":"Hi."}""")
        val options = Json.parseToJsonElement("""{"order":["openai"],"allow_fallbacks":false}""").jsonObject

        cleanup().clean(options)

        val body = requestBody()
        assertEquals(options, body["provider"])
        assertFalse("providerOptions" in body)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun contentThatIsNotTheSchemaFails() {
        respondCompletion("Hello, world.")

        assertEquals("Unexpected response from OpenRouter", failure { cleanup().clean() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun malformedResponsesFail() {
        val bodies = listOf(
            completion(content = null),
            completion("""{"text":"  "}"""),
            """{"id":"gen-1","choices":[]}""",
            """{"id":"gen-1","choices":[],"error":null}""",
            "<html>oops</html>",
            "",
        )
        for (body in bodies) {
            respond(200, body)
            assertEquals(body, "Unexpected response from OpenRouter", failure { cleanup().clean() }.message)
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun httpErrorsMapToReadableMessages() {
        val cases = listOf(
            401 to "OpenRouter rejected the API key. Check it in Iris settings.",
            402 to "OpenRouter account is out of credits",
            429 to "OpenRouter rate limit reached",
            502 to "OpenRouter: the model's provider failed (HTTP 502)",
            503 to "No OpenRouter provider available for this model (check provider options)",
            500 to "OpenRouter server error (HTTP 500)",
        )
        for ((code, message) in cases) {
            respond(code, """{"error":{"code":$code,"message":"ignored"}}""")
            assertEquals(message, failure { cleanup().clean() }.message)
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun clientErrorShowsOpenRouterMessageWhenPresent() {
        respond(400, """{"error":{"code":400,"message":"Model not found"}}""")
        assertEquals("OpenRouter error: Model not found", failure { cleanup().clean() }.message)

        // 403 is moderation or forbidden on OpenRouter, not a key problem.
        respond(403, """{"error":{"code":403,"message":"Input was flagged"}}""")
        assertEquals("OpenRouter error: Input was flagged", failure { cleanup().clean() }.message)

        respond(404, "not json")
        assertEquals("OpenRouter request failed (HTTP 404)", failure { cleanup().clean() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun errorInA200ResponseFails() {
        respond(200, """{"error":{"code":502,"message":"Provider returned error"}}""")
        assertEquals("OpenRouter error: Provider returned error", failure { cleanup().clean() }.message)

        respond(
            200,
            """{"id":"gen-1","choices":[{"index":0,"message":{"role":"assistant","content":""},
               "finish_reason":"error","error":{"code":502,"message":"Upstream failed"}}]}"""
        )
        assertEquals("OpenRouter error: Upstream failed", failure { cleanup().clean() }.message)

        respond(200, completion(content = """{"text":"Hel""", finishReason = "error"))
        assertEquals("OpenRouter error: generation failed", failure { cleanup().clean() }.message)
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

        // OpenRouter reports a refusal with finish_reason content_filter: the refusal wins.
        respond(200, completion(refusal = "No.", finishReason = "content_filter"))
        assertEquals("Model refused: No.", failure { cleanup().clean() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun cancellingBeforeTheResponseCancelsTheCall() = runBlocking {
        server.enqueue(
            MockResponse.Builder().body(completion("""{"text":"late"}""")).headersDelay(10, TimeUnit.SECONDS).build()
        )
        val cancelled = CountDownLatch(1)
        val httpClient = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) = cancelled.countDown()
            })
            .build()

        val job = launch(Dispatchers.IO) { cleanup(httpClient = httpClient).clean() }
        server.takeRequest() // The call is in flight.
        job.cancelAndJoin()

        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun missingApiKeyFailsWithoutCalling() {
        val error = failure { cleanup(apiKey = null).clean() }

        assertEquals("Add your OpenRouter API key in Iris settings", error.message)
        assertEquals(0, server.requestCount)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun listModelsRequestsStructuredOutputModelsSortedWithoutKey() = runBlocking {
        respond(
            200,
            """{"data":[
                {"id":"openai/gpt-6-luna","name":"Luna"},
                {"id":"anthropic/claude-haiku-5"},
                {"id":"openai/gpt-6-luna:batch"},
                {"id":" "},
                {"name":"no id"},
                "odd"
            ]}"""
        )

        val models = cleanup(apiKey = null).listModels()

        assertEquals(
            listOf(
                GatewayModel("anthropic/claude-haiku-5", null, emptyList(), null),
                GatewayModel("openai/gpt-6-luna", null, emptyList(), null),
            ),
            models
        )
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/models?supported_parameters=structured_outputs", request.target)
        assertNull(request.headers["Authorization"])
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun listModelsOrdersReasoningEffortsLowToHigh() = runBlocking {
        val body = """{"data":[
            {"id":"a/luna","reasoning":{"mandatory":false,"default_enabled":true,
                "supported_efforts":["max","xhigh","high","medium","low","none"],"default_effort":"medium"}},
            {"id":"b/odd","reasoning":{"supported_efforts":["turbo","high","minimal"]}},
            {"id":"c/no-reasoning"},
            {"id":"d/no-efforts","reasoning":{"mandatory":true}},
            {"id":"e/null-efforts","reasoning":{"supported_efforts":null}},
            {"id":"f/empty-efforts","reasoning":{"supported_efforts":[]}},
            {"id":"g/string-efforts","reasoning":{"supported_efforts":"low"}}
        ]}"""
        respond(200, body)

        val expected = listOf(
            "a/luna" to listOf("none", "low", "medium", "high", "xhigh", "max"),
            "b/odd" to listOf("minimal", "high", "turbo"),
            "c/no-reasoning" to null,
            "d/no-efforts" to null,
            "e/null-efforts" to null,
            "f/empty-efforts" to null,
            "g/string-efforts" to null,
        )
        assertEquals(expected, cleanup().listModels().map { it.id to it.reasoningEfforts })
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun listModelsMalformedResponseFails() {
        for (body in listOf("<html>oops</html>", """{"object":"list"}""", """{"data":{}}""")) {
            respond(200, body)
            assertEquals("Unexpected response from OpenRouter", failure { cleanup().listModels() }.message)
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun listModelsHttpErrorsMapToReadableMessages() {
        val cases = listOf(
            429 to "OpenRouter rate limit reached",
            500 to "OpenRouter server error (HTTP 500)",
            404 to "OpenRouter request failed (HTTP 404)",
        )
        for ((code, message) in cases) {
            respond(code, "not json")
            assertEquals(message, failure { cleanup().listModels() }.message)
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun listModelsTimeoutHasItsOwnMessage() {
        server.enqueue(
            MockResponse.Builder().body("""{"data":[]}""").headersDelay(2, TimeUnit.SECONDS).build()
        )

        assertEquals("Loading models timed out", failure { cleanup(callTimeoutMs = 200).listModels() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun warmUpSendsHeadOfModelsWithoutKey() {
        respond(200, "")

        cleanup().warmUp()

        val request = server.takeRequest()
        assertEquals("HEAD", request.method)
        assertEquals("/api/v1/models", request.target)
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
        val closedUrl = closed.url("/api/v1/models").toString()
        closed.close() // Connection refused.
        val cleanup = cleanup(httpClient = httpClient, modelsUrl = closedUrl)

        cleanup.warmUp()
        while (httpClient.dispatcher.runningCallsCount() > 0) Thread.sleep(10)
        respondCompletion("""{"text":"Hi."}""")

        assertEquals("Hi.", cleanup.clean())
    }

    private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content

    private companion object {
        const val TEST_TIMEOUT_MS = 15_000L
    }
}
