/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.cleanup.compatible

import dev.jamius.iris.cleanup.CleanupException
import dev.jamius.iris.cleanup.GatewayModel
import dev.jamius.iris.cleanup.vercel.PLAIN_TRANSCRIPT_FRAMING
import dev.jamius.iris.cleanup.vercel.TRANSCRIPT_FRAMING
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
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiCompatibleCleanupTest {

    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun cleanup(
        apiKey: String? = "gsk-test",
        sendJsonSchema: Boolean = true,
        httpClient: OkHttpClient = OkHttpClient(),
    ) = OpenAiCompatibleCleanup(
        httpClient,
        name = "Groq",
        // A trailing '/' is dropped: no double slash, and no /v1 is added.
        baseUrl = server.url("/openai/v1/").toString(),
        sendJsonSchema = sendJsonSchema,
        apiKey = { apiKey },
        callTimeoutMs = 10_000
    )

    private suspend fun OpenAiCompatibleCleanup.clean(model: String = "m", effort: String? = "low") =
        cleanup("hello", "Clean it.", model, effort, null)

    private fun respond(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun completion(
        content: String? = null,
        refusal: String? = null,
        finishReason: String? = "stop",
    ) = buildJsonObject {
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

    private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content

    @Test(timeout = TEST_TIMEOUT_MS)
    fun urls() {
        assertEquals("https://a.example/v1/chat/completions", chatCompletionsUrl("https://a.example/v1/"))
        assertEquals("https://a.example/v1/models", modelsUrl("https://a.example/v1"))
        assertEquals("https://a.example/chat/completions", chatCompletionsUrl("https://a.example"))
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun schemaOnSendsStructuredRequestAndParsesText() = runBlocking {
        respond(200, completion("""{"text":"  Hello, world.  "}"""))

        assertEquals("Hello, world.", cleanup().clean(model = "llama-4"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/openai/v1/chat/completions", request.target)
        assertEquals("Bearer gsk-test", request.headers["Authorization"])
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("llama-4", body.string("model"))
        assertEquals("low", body.string("reasoning_effort"))
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals("Clean it.\n\n$TRANSCRIPT_FRAMING", messages[0].string("content"))
        assertEquals("hello", messages[1].string("content"))
        assertEquals("json_schema", body["response_format"]!!.jsonObject.string("type"))
        assertFalse("provider" in body)
        assertFalse("providerOptions" in body)
        assertFalse("" in body)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun schemaOffSendsPlainRequestAndReturnsTrimmedContent() = runBlocking {
        respond(200, completion("  Hello, world.\n"))

        assertEquals("Hello, world.", cleanup(sendJsonSchema = false).clean())

        val body = requestBody()
        assertFalse("response_format" in body)
        val system = body["messages"]!!.jsonArray[0].jsonObject.string("content")
        assertEquals("Clean it.\n\n$PLAIN_TRANSCRIPT_FRAMING", system)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun schemaOffStripsLeadingThinkBlock() = runBlocking {
        respond(200, completion("\n<THINK>Plan:\n<b>fix</b> commas</think>\n\n Hello, world. "))

        assertEquals("Hello, world.", cleanup(sendJsonSchema = false).clean())
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun thinkBlockStripping() {
        assertEquals("Hi.", withoutThinkBlock("  <think>a\nb</think> Hi. "))
        assertEquals("", withoutThinkBlock("<think>only thoughts</think>"))
        // Only a leading block: text without one, or a later one, is untouched (just trimmed).
        assertEquals("Hello, world.", withoutThinkBlock(" Hello, world.\n"))
        assertEquals("Hi <think>x</think>", withoutThinkBlock("Hi <think>x</think>"))
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun schemaOffThinkOnlyContentFails() {
        respond(200, completion("<think>thoughts</think>  "))

        assertEquals("Unexpected response from Groq", failure { cleanup(sendJsonSchema = false).clean() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun schemaOffBlankContentFails() {
        respond(200, completion("   "))

        assertEquals("Unexpected response from Groq", failure { cleanup(sendJsonSchema = false).clean() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun schemaOnContentThatIsNotTheSchemaFails() {
        respond(200, completion("Hello, world."))

        assertEquals("Unexpected response from Groq", failure { cleanup().clean() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun omitsReasoningEffortForModelDefault() = runBlocking {
        respond(200, completion("""{"text":"Hi."}"""))

        cleanup().clean(effort = null)

        assertFalse("reasoning_effort" in requestBody())
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun missingKeyOrBlankModelFailsWithoutCalling() {
        assertEquals(
            "Add your Groq API key in Iris settings",
            failure { cleanup(apiKey = null).clean() }.message
        )
        assertEquals(
            "Set a cleanup model for Groq in Iris settings",
            failure { cleanup().clean(model = " ") }.message
        )
        assertEquals(0, server.requestCount)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun httpErrorsNameTheProvider() {
        val cases = listOf(
            401 to "Groq rejected the API key. Check it in Iris settings.",
            429 to "Groq rate limit or quota reached",
            500 to "Groq server error (HTTP 500)",
        )
        for ((code, message) in cases) {
            respond(code, """{"error":{"message":"ignored"}}""")
            assertEquals(message, failure { cleanup().clean() }.message)
        }
        respond(400, """{"error":{"message":"response_format is not supported"}}""")
        assertEquals("Groq error: response_format is not supported", failure { cleanup().clean() }.message)
        // A plain-string error (some local servers): the generic message.
        respond(400, """{"error":"bad request"}""")
        assertEquals("Groq request failed (HTTP 400)", failure { cleanup().clean() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun cutOffAndRefusalFail() {
        respond(200, completion("""{"text":"Hel""", finishReason = "length"))
        assertEquals("Cleanup output was cut off", failure { cleanup().clean() }.message)

        respond(200, completion(refusal = "I can't help with that."))
        assertEquals("Model refused: I can't help with that.", failure { cleanup().clean() }.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun listModelsSendsKeyAndKeepsEveryId() = runBlocking {
        respond(
            200,
            """{"object":"list","data":[
                {"id":"llama-4","object":"model","type":"chat"},
                {"id":"whisper-large-v3","object":"model"},
                {"id":" "}
            ]}"""
        )

        val models = cleanup().listModels()

        assertEquals(
            listOf(
                GatewayModel("llama-4", "chat", emptyList(), null),
                GatewayModel("whisper-large-v3", null, emptyList(), null),
            ),
            models
        )
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/openai/v1/models", request.target)
        assertEquals("Bearer gsk-test", request.headers["Authorization"])
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun listModelsWithoutKeyFailsWithoutCalling() {
        assertEquals(
            "Set your Groq API key to load models",
            failure { cleanup(apiKey = null).listModels() }.message
        )
        assertEquals(0, server.requestCount)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun warmUpIsAKeylessHeadAndCleanupReusesItsConnection() = runBlocking {
        respond(401, "")
        respond(200, completion("""{"text":"Hi."}"""))
        val httpClient = OkHttpClient()
        val cleanup = cleanup(httpClient = httpClient)

        cleanup.warmUp()
        val warmUp = server.takeRequest()
        assertEquals("HEAD", warmUp.method)
        assertEquals("/openai/v1/models", warmUp.target)
        assertNull(warmUp.headers["Authorization"])
        while (httpClient.connectionPool.idleConnectionCount() == 0) Thread.sleep(10) // Response consumed.
        assertEquals("Hi.", cleanup.clean())

        val request = server.takeRequest()
        assertEquals(warmUp.connectionIndex, request.connectionIndex)
        assertTrue(request.exchangeIndex > 0)
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 15_000L
    }
}
