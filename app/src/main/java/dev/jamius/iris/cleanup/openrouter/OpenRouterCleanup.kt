/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.cleanup.openrouter

import android.util.Log
import dev.jamius.iris.cleanup.CleanupException
import dev.jamius.iris.cleanup.CleanupProvider
import dev.jamius.iris.cleanup.GatewayModel
import dev.jamius.iris.cleanup.vercel.chatRequest
import dev.jamius.iris.cleanup.vercel.modelsNetworkErrorMessage
import dev.jamius.iris.cleanup.vercel.networkErrorMessage
import dev.jamius.iris.cleanup.vercel.parseCleanedText
import dev.jamius.iris.cleanup.vercel.parseCompletion
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

/** Cleanup through OpenRouter's OpenAI-compatible Chat Completions API: a structured output. */
class OpenRouterCleanup(
    httpClient: OkHttpClient,
    private val apiKey: suspend () -> String?,
    private val url: String = OPENROUTER_CHAT_COMPLETIONS_URL,
    private val modelsUrl: String = OPENROUTER_MODELS_URL,
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
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: throw CleanupException("Add your OpenRouter API key in Iris settings")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .post(
                chatRequest(text, systemPrompt, model, reasoningEffort, OPENROUTER_OPTIONS_KEY, providerOptions)
                    .toRequestBody(JSON_MEDIA_TYPE)
            )
            .build()
        val (code, body) = execute(request, client = cleanupClient)
        if (code !in 200..299) throw CleanupException(openRouterHttpErrorMessage(code, body))
        parseOpenRouterError(body)?.let { throw CleanupException("OpenRouter error: $it") }
        val completion = parseCompletion(body) ?: throw CleanupException(UNEXPECTED_RESPONSE)
        if (completion.finishReason == "length") throw CleanupException("Cleanup output was cut off")
        // Before content_filter: OpenRouter reports a refusal with finish_reason content_filter.
        if (completion.content.isNullOrEmpty() && !completion.refusal.isNullOrBlank()) {
            throw CleanupException("Model refused: ${completion.refusal}")
        }
        if (completion.finishReason == "content_filter") throw CleanupException("Cleanup blocked by content filter")
        return completion.content?.let(::parseCleanedText) ?: throw CleanupException(UNEXPECTED_RESPONSE)
    }

    /** `HEAD` of the public model catalog (no key): only opens the connection that [cleanup] then reuses. */
    override fun warmUp() {
        val request = Request.Builder().url(modelsUrl).head().build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d(TAG, "Warm-up failed: $e")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { Log.d(TAG, "Warm-up: ${it.protocol} ${it.code}") }
            }
        })
    }

    /** The catalog is public, so no key is sent. Only models with structured output are requested. */
    override suspend fun listModels(): List<GatewayModel> {
        val request = Request.Builder().url("$modelsUrl?$STRUCTURED_OUTPUTS_QUERY").build()
        val (code, body) = execute(request, networkError = ::modelsNetworkErrorMessage)
        if (code !in 200..299) throw CleanupException(openRouterHttpErrorMessage(code, body))
        return withContext(Dispatchers.Default) { parseOpenRouterModels(body) }
            ?: throw CleanupException(UNEXPECTED_RESPONSE)
    }

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
        const val TAG = "OpenRouterCleanup"
        const val CALL_TIMEOUT_MS = 30_000L
        const val UNEXPECTED_RESPONSE = "Unexpected response from OpenRouter"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
