/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.cleanup.vercel

import android.util.Log
import dev.jamius.iris.cleanup.CleanupException
import dev.jamius.iris.cleanup.CleanupModel
import dev.jamius.iris.cleanup.CleanupProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cleanup through Vercel AI Gateway's OpenAI-compatible Chat Completions API: a streamed structured output. */
class VercelGatewayCleanup(
    httpClient: OkHttpClient,
    private val apiKey: suspend () -> String?,
    private val url: String = CHAT_COMPLETIONS_URL,
    private val modelsUrl: String = MODELS_URL,
    callTimeoutMs: Long = CALL_TIMEOUT_MS,
    stallTimeoutMs: Long = STALL_TIMEOUT_MS,
) : CleanupProvider {

    // Both share the app client's connection pool and dispatcher.
    private val httpClient = httpClient.newBuilder().callTimeout(callTimeoutMs, MILLISECONDS).build()

    // No call timeout, so a long cleanup isn't cut off while it streams. A stalled one ends after stallTimeoutMs
    // without data: the read timeout covers a silent socket (incl. waiting for the headers), readStream() a stream
    // that only sends heartbeat comments.
    private val streamClient = httpClient.newBuilder()
        .callTimeout(0, MILLISECONDS)
        .readTimeout(stallTimeoutMs, MILLISECONDS)
        .build()
    private val stallTimeoutNanos = MILLISECONDS.toNanos(stallTimeoutMs)

    override suspend fun cleanup(text: String, systemPrompt: String, model: String, reasoningEffort: String?): String {
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: throw CleanupException("Add your Vercel AI Gateway API key in Iris settings")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .post(chatRequest(text, systemPrompt, model, reasoningEffort).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val content = StringBuilder()
        contentDeltas(request).collect { content.append(it) }
        return parseCleanedText(content.toString()) ?: throw CleanupException(UNEXPECTED_RESPONSE)
    }

    /**
     * The `choices[0].delta.content` pieces of the streamed response, read on an OkHttp thread and delivered in the
     * collector's context. Cancelling the collector cancels the call. Fails with [CleanupException].
     */
    private fun contentDeltas(request: Request): Flow<String> = callbackFlow {
        val call = streamClient.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(CleanupException(networkErrorMessage(e)))
            }

            override fun onResponse(call: Call, response: Response) {
                val error = try {
                    response.use { readStream(it) { delta -> trySendBlocking(delta) } }
                    null
                } catch (e: IOException) {
                    CleanupException(networkErrorMessage(e))
                } catch (e: CleanupException) {
                    e
                } catch (e: Exception) {
                    // Never escapes to OkHttp, so the flow always closes.
                    Log.e(TAG, "Reading the cleanup stream failed", e)
                    CleanupException(UNEXPECTED_RESPONSE)
                }
                close(error)
            }
        })
        awaitClose { call.cancel() }
    }

    /**
     * Reads the SSE body (`data: {chunk}` events, `: heartbeat` comments, `data: [DONE]` last), passing each content
     * delta to [onContent]. Throws [CleanupException] for HTTP errors, error events, refusals, a `length` or
 * `content_filter` finish reason and malformed data.
     */
    private fun readStream(response: Response, onContent: (String) -> Unit) {
        if (!response.isSuccessful) throw CleanupException(httpErrorMessage(response.code, response.body.string()))
        val source = response.body.source()
        val data = StringBuilder()
        val refusal = StringBuilder()
        var hasContent = false
        var lastData = System.nanoTime()
        while (true) {
            val line = source.readUtf8Line()
            if (line.isNullOrEmpty()) {
                // A blank line ends an event; so does the end of the body.
                if (data.isNotEmpty()) {
                    val payload = data.toString()
                    data.clear()
                    if (payload == DONE) break
                    val chunk = parseStreamChunk(payload) ?: throw CleanupException(UNEXPECTED_RESPONSE)
                    chunk.error?.let { throw CleanupException(it) }
                    chunk.refusal?.let(refusal::append)
                    chunk.content?.takeIf { it.isNotEmpty() }?.let {
                        hasContent = true
                        onContent(it)
                    }
                    when (chunk.finishReason) {
                        "length" -> throw CleanupException("Cleanup output was cut off")
                        "content_filter" -> throw CleanupException("Cleanup blocked by content filter")
                    }
                }
                if (line == null) break
            } else if (line.startsWith(DATA_PREFIX)) {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix(DATA_PREFIX).removePrefix(" "))
                lastData = System.nanoTime()
            } else if (System.nanoTime() - lastData > stallTimeoutNanos) {
                // Heartbeats keep the socket busy, so the read timeout never fires.
                throw InterruptedIOException("Stream stalled")
            }
        }
        if (!hasContent && refusal.isNotBlank()) throw CleanupException("Model refused: $refusal")
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
    override suspend fun listModels(): List<CleanupModel> {
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
    ): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        val call = httpClient.newCall(request)
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
        const val STALL_TIMEOUT_MS = 15_000L
        const val DATA_PREFIX = "data:"
        const val DONE = "[DONE]"
        const val UNEXPECTED_RESPONSE = "Unexpected response from AI Gateway"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
