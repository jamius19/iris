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
import kotlinx.serialization.json.JsonNull
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
        callTimeoutMs = callTimeoutMs
    )

    private fun respond(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    /** A chat completion whose message content is [content] (itself a JSON string for structured output). */
    private fun completion(content: String) = buildJsonObject {
        put("id", "chatcmpl-1")
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("index", 0)
                put("message", buildJsonObject {
                    put("role", "assistant")
                    put("content", content)
                })
                put("finish_reason", "stop")
            })
        })
    }.toString()

    private fun failure(block: suspend () -> Unit): CleanupException =
        assertThrows(CleanupException::class.java) { runBlocking { block() } }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun sendsStructuredRequestAndReturnsCleanedText() = runBlocking {
        respond(200, completion("""{"text":"  Hello, world.  "}"""))

        val text = cleanup().cleanup("um hello world", "Clean it.", "openai/gpt-6-luna")

        assertEquals("Hello, world.", text)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/chat/completions", request.target)
        assertEquals("Bearer vck-test", request.headers["Authorization"])
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("openai/gpt-6-luna", body.string("model"))
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
    fun contentThatIsNotTheSchemaFails() {
        respond(200, completion("Hello, world."))

        val error = failure { cleanup().cleanup("hello", "Clean it.", "m") }
        assertEquals("Unexpected response from AI Gateway", error.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun blankCleanedTextFails() {
        respond(200, completion("""{"text":"  "}"""))

        val error = failure { cleanup().cleanup("hello", "Clean it.", "m") }
        assertEquals("Unexpected response from AI Gateway", error.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun missingChoicesFails() {
        respond(200, """{"id":"chatcmpl-1","choices":[]}""")

        val error = failure { cleanup().cleanup("hello", "Clean it.", "m") }
        assertEquals("Unexpected response from AI Gateway", error.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun nonJsonBodyFails() {
        respond(200, "<html>oops</html>")

        val error = failure { cleanup().cleanup("hello", "Clean it.", "m") }
        assertEquals("Unexpected response from AI Gateway", error.message)
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
            assertEquals(message, failure { cleanup().cleanup("hello", "Clean it.", "m") }.message)
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun clientErrorShowsGatewayMessageWhenPresent() {
        respond(400, """{"error":{"message":"Model not found","type":"invalid_request_error"}}""")
        assertEquals(
            "AI Gateway error: Model not found",
            failure { cleanup().cleanup("hello", "Clean it.", "m") }.message
        )

        respond(404, "not json")
        assertEquals(
            "AI Gateway request failed (HTTP 404)",
            failure { cleanup().cleanup("hello", "Clean it.", "m") }.message
        )
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun slowResponseTimesOut() {
        server.enqueue(
            MockResponse.Builder()
                .body(completion("""{"text":"late"}"""))
                .headersDelay(2, TimeUnit.SECONDS)
                .build()
        )

        val error = failure { cleanup(callTimeoutMs = 200).cleanup("hello", "Clean it.", "m") }
        assertEquals("Cleanup timed out", error.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun refusalIsTheErrorMessage() {
        val body = buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("message", buildJsonObject {
                        put("role", "assistant")
                        put("content", JsonNull)
                        put("refusal", "I can't help with that.")
                    })
                })
            })
        }.toString()
        respond(200, body)

        val error = failure { cleanup().cleanup("hello", "Clean it.", "m") }
        assertEquals("Model refused: I can't help with that.", error.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun cancellingTheCoroutineCancelsTheCall() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(completion("""{"text":"late"}"""))
                .headersDelay(10, TimeUnit.SECONDS)
                .build()
        )
        val cancelled = CountDownLatch(1)
        val httpClient = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) = cancelled.countDown()
            })
            .build()

        val job = launch(Dispatchers.IO) { cleanup(httpClient = httpClient).cleanup("hello", "Clean it.", "m") }
        server.takeRequest() // The call is in flight.
        job.cancelAndJoin()

        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun missingApiKeyFailsWithoutCalling() {
        val error = failure { cleanup(apiKey = null).cleanup("hello", "Clean it.", "m") }

        assertEquals("Add your Vercel AI Gateway API key in Iris settings", error.message)
        assertEquals(0, server.requestCount)
    }

    private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content

    private companion object {
        const val TEST_TIMEOUT_MS = 15_000L
    }
}
