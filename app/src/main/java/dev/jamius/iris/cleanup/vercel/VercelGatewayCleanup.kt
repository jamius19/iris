/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.cleanup.vercel

import android.util.Log
import dev.jamius.iris.cleanup.CleanupException
import dev.jamius.iris.cleanup.CleanupProvider
import dev.jamius.iris.cleanup.GatewayModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cleanup through Vercel AI Gateway's OpenAI-compatible Chat Completions API: a structured output. */
class VercelGatewayCleanup(
    httpClient: OkHttpClient,
    private val apiKey: suspend () -> String?,
    private val url: String = CHAT_COMPLETIONS_URL,
    private val modelsUrl: String = MODELS_URL,
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
            ?: throw CleanupException("Add your Vercel AI Gateway API key in Iris settings")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .post(
                chatRequest(text, systemPrompt, model, reasoningEffort, GATEWAY_OPTIONS_KEY, providerOptions)
                    .toRequestBody(JSON_MEDIA_TYPE)
            )
            .build()
        val (code, body) = execute(request, client = cleanupClient)
        if (code !in 200..299) throw CleanupException(httpErrorMessage(code, body))
        val completion = parseCompletion(body) ?: throw CleanupException(UNEXPECTED_RESPONSE)
        when (completion.finishReason) {
            "length" -> throw CleanupException("Cleanup output was cut off")
            "content_filter" -> throw CleanupException("Cleanup blocked by content filter")
        }
        if (completion.content.isNullOrEmpty() && !completion.refusal.isNullOrBlank()) {
            throw CleanupException("Model refused: ${completion.refusal}")
        }
        return completion.content?.let(::parseCleanedText) ?: throw CleanupException(UNEXPECTED_RESPONSE)
    }

    /**
     * `HEAD` of the public model catalog: answered from Vercel's edge cache with no body and no key needed, so it
     * only opens the (HTTP/2) connection that [cleanup] then reuses from the shared pool.
     */
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

    /** The catalog is public, so no key is sent (an invalid one would get a 401). */
    override suspend fun listModels(): List<GatewayModel> {
        val request = Request.Builder().url(modelsUrl).build()
        val (code, body) = execute(request, networkError = ::modelsNetworkErrorMessage)
        if (code !in 200..299) throw CleanupException(httpErrorMessage(code, body))
        // The catalog is several hundred KB; parse it off the caller's (main) thread.
        return withContext(Dispatchers.Default) { parseModelsResponse(body) }
            ?: throw CleanupException(UNEXPECTED_RESPONSE)
    }

    /** Returns the HTTP code and body. Cancelling the coroutine cancels the call. */
    private suspend fun execute(
        request: Request,
        networkError: (IOException) -> String = ::networkErrorMessage,
        client: OkHttpClient = httpClient,
    ): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(CleanupException(networkError(e)))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { it.code to it.body.string() }
                } catch (e: IOException) {
                    cont.resumeWithException(CleanupException(networkError(e)))
                    return
                }
                cont.resume(result)
            }
        })
    }

    private companion object {
        const val TAG = "VercelGatewayCleanup"
        const val CALL_TIMEOUT_MS = 30_000L
        const val UNEXPECTED_RESPONSE = "Unexpected response from AI Gateway"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
