/*
* Author: Jamius Siam
* Since: 12/11/2026
*/

package dev.jamius.iris.stt.vercel

import android.util.Log
import dev.jamius.iris.cleanup.vercel.httpErrorMessage
import dev.jamius.iris.stt.SpeechToTextProvider
import dev.jamius.iris.stt.SttException
import dev.jamius.iris.stt.await
import dev.jamius.iris.stt.uploadReadTimeoutMs
import dev.jamius.iris.stt.wavBytes
import kotlinx.coroutines.flow.Flow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit.MILLISECONDS

/**
 * Vercel AI Gateway transcription of a recording uploaded after stop (`POST` of base64 WAV as JSON), for models
 * without streaming support: no live text. No size limit is documented, so it is one upload.
 */
class VercelGatewayFileTranscription(
    private val httpClient: OkHttpClient,
    private val apiKey: suspend () -> String?,
    private val url: String = TRANSCRIPTION_URL,
    private val warmUpUrl: String = GATEWAY_WARM_UP_URL,
) : SpeechToTextProvider {

    /** `System.nanoTime()` of the last warm-up, or null. */
    @Volatile
    private var warmedUpAt: Long? = null

    override val streams: Boolean get() = false

    override fun sampleRate(model: String): Int = SAMPLE_RATE

    override suspend fun transcribe(
        audio: Flow<ByteArray>,
        model: String,
        language: String,
        timeoutMs: Long,
        onPartial: (String) -> Unit,
    ): String {
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: throw SttException("Add your Vercel AI Gateway API key in Iris settings")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .header("ai-gateway-protocol-version", PROTOCOL_VERSION)
            .header("ai-gateway-auth-method", "api-key")
            .header("ai-transcription-model-specification-version", SPECIFICATION_VERSION)
            .apply {
                try {
                    header("ai-model-id", model)
                } catch (e: IllegalArgumentException) {
                    throw SttException("Invalid model id: $model")
                }
            }
        val chunks = ArrayList<ByteArray>()
        audio.collect { chunks += it }
        val stoppedAt = System.nanoTime()
        val wav = wavBytes(chunks, SAMPLE_RATE)
        val body = UploadBody(wav, providerOptions(model, language, streaming = false))
        val audioMs = chunks.sumOf { it.size } * 1000L / (SAMPLE_RATE * 2)
        val client = httpClient.newBuilder()
            .readTimeout(uploadReadTimeoutMs(timeoutMs, audioMs), MILLISECONDS)
            .build()
        val (code, response) = try {
            client.newCall(request.post(body).build()).await()
        } catch (e: InterruptedIOException) {
            throw SttException(
                if (body.sent) "Timed out waiting for the transcript" else "Timed out sending audio to AI Gateway"
            )
        } catch (e: IOException) {
            throw SttException(gatewayNetworkErrorMessage(e))
        }
        if (code !in 200..299) throw SttException(httpErrorMessage(code, response))
        val (text, warnings) = parseUploadResponse(response)
            ?: throw SttException("Unexpected response from AI Gateway")
        warnings.forEach { Log.w(TAG, "Upload warning: $it") }
        val ms = (System.nanoTime() - stoppedAt) / 1_000_000
        Log.d(TAG, "Timing: upload ${wav.size / 1024} KB, stop->response $ms ms")
        return text.trim()
    }

    /**
     * `HEAD` of the public model catalog without a key, leaving an idle (HTTP/2) connection in the shared pool for
     * the upload. Skipped within [WARM_UP_WINDOW_MS] of an earlier warm-up (an HTTP/2 connection returns to the
     * pool).
     */
    override fun warmUp() {
        val now = System.nanoTime()
        warmedUpAt?.let { if (now - it < WARM_UP_WINDOW_MS * 1_000_000) return }
        warmedUpAt = now
        val request = Request.Builder().url(warmUpUrl).head().build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d(TAG, "Warm-up failed: $e")
                // No connection was left in the pool: let the next call try again (e.g. once back online).
                if (warmedUpAt == now) warmedUpAt = null
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { Log.d(TAG, "Warm-up: ${it.protocol} ${it.code}") }
            }
        })
    }

    private companion object {
        const val TAG = "VercelGatewayFileStt"

        /** As Handy uploads it. */
        const val SAMPLE_RATE = 16_000

        /** Inside OkHttp's 5 min pool keep-alive. */
        const val WARM_UP_WINDOW_MS = 180_000L
    }
}
