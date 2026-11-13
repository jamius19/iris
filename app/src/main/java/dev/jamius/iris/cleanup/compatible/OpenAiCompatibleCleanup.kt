/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.cleanup.compatible

import android.util.Log
import dev.jamius.iris.cleanup.CleanupException
import dev.jamius.iris.cleanup.CleanupProvider
import dev.jamius.iris.cleanup.GatewayModel
import dev.jamius.iris.cleanup.vercel.chatRequest
import dev.jamius.iris.cleanup.vercel.httpErrorMessage
import dev.jamius.iris.cleanup.vercel.modelsNetworkErrorMessage
import dev.jamius.iris.cleanup.vercel.networkErrorMessage
import dev.jamius.iris.cleanup.vercel.parseCleanedText
import dev.jamius.iris.cleanup.vercel.parseCompletion
import dev.jamius.iris.cleanup.vercel.parseModelsResponse
import dev.jamius.iris.stt.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit.MILLISECONDS

/**
 * Cleanup through a user-added OpenAI-compatible Chat Completions server ([name] in messages) at [baseUrl]. With
 * [sendJsonSchema] the request asks for a strict `{ "text": string }` structured output; without it the reply's
 * content is the cleaned text (minus a leading `<think>` block). No provider options.
 */
class OpenAiCompatibleCleanup(
    httpClient: OkHttpClient,
    private val name: String,
    private val baseUrl: String,
    private val sendJsonSchema: Boolean,
    private val apiKey: suspend () -> String?,
    callTimeoutMs: Long = CALL_TIMEOUT_MS,
) : CleanupProvider {

    // Both share the app client's connection pool and dispatcher.
    private val httpClient = httpClient.newBuilder().callTimeout(callTimeoutMs, MILLISECONDS).build()

    // The response arrives only once the model is done, so no read timeout: the call timeout (30 s, the max of the
    // service's cleanupTimeoutSeconds deadline) is the only limit.
    private val cleanupClient = this.httpClient.newBuilder().readTimeout(0, MILLISECONDS).build()

    override suspend fun cleanup(
        text: String,
        systemPrompt: String,
        model: String,
        reasoningEffort: String?,
        providerOptions: JsonObject?,
    ): String {
        if (model.isBlank()) throw CleanupException("Set a cleanup model for $name in Iris settings")
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: throw CleanupException("Add your $name API key in Iris settings")
        val body = chatRequest(text, systemPrompt, model, reasoningEffort, "", null, jsonSchema = sendJsonSchema)
        val request = Request.Builder()
            .url(chatCompletionsUrl(baseUrl))
            .header("Authorization", "Bearer $key")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val (code, response) = execute(request, client = cleanupClient)
        if (code !in 200..299) throw CleanupException(httpErrorMessage(code, response, name))
        val completion = parseCompletion(response) ?: throw CleanupException(unexpectedResponse())
        when (completion.finishReason) {
            "length" -> throw CleanupException("Cleanup output was cut off")
            "content_filter" -> throw CleanupException("Cleanup blocked by content filter")
        }
        if (completion.content.isNullOrEmpty() && !completion.refusal.isNullOrBlank()) {
            throw CleanupException("Model refused: ${completion.refusal}")
        }
        val content = completion.content
        val cleaned = if (sendJsonSchema) {
            content?.let(::parseCleanedText)
        } else {
            content?.let(::withoutThinkBlock)?.takeIf { it.isNotEmpty() }
        }
        return cleaned ?: throw CleanupException(unexpectedResponse())
    }

    /** Keyless `HEAD` of the models list: only opens the connection that [cleanup] then reuses (a 401 is fine). */
    override fun warmUp() {
        val request = Request.Builder().url(modelsUrl(baseUrl)).head().build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d(TAG, "Warm-up failed: $e")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { Log.d(TAG, "Warm-up: ${it.protocol} ${it.code}") }
            }
        })
    }

    /** Every model the key can list (no type filter: some servers use other types, e.g. `chat`). */
    override suspend fun listModels(): List<GatewayModel> {
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: throw CleanupException("Set your $name API key to load models")
        val request = Request.Builder().url(modelsUrl(baseUrl)).header("Authorization", "Bearer $key").build()
        val (code, body) = execute(request, networkError = ::modelsNetworkErrorMessage)
        if (code !in 200..299) throw CleanupException(httpErrorMessage(code, body, name))
        return withContext(Dispatchers.Default) { parseModelsResponse(body) }
            ?: throw CleanupException(unexpectedResponse())
    }

    private fun unexpectedResponse() = "Unexpected response from $name"

    /** Returns the HTTP code and body. Cancelling the coroutine cancels the call. */
    private suspend fun execute(
        request: Request,
        client: OkHttpClient = httpClient,
        networkError: (IOException) -> String = ::networkErrorMessage,
    ): Pair<Int, String> = try {
        client.newCall(request).await()
    } catch (e: IOException) {
        throw CleanupException(networkError(e))
    }

    private companion object {
        const val TAG = "OpenAiCompatibleCleanup"
        const val CALL_TIMEOUT_MS = 30_000L
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
