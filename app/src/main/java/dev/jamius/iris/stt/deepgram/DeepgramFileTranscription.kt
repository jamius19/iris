/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.stt.deepgram

import android.util.Log
import dev.jamius.iris.stt.SentBody
import dev.jamius.iris.stt.SpeechToTextProvider
import dev.jamius.iris.stt.SttException
import dev.jamius.iris.stt.await
import dev.jamius.iris.stt.uploadReadTimeoutMs
import dev.jamius.iris.stt.wavBytes
import kotlinx.coroutines.flow.Flow
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

/**
 * Deepgram pre-recorded transcription (`POST /v1/listen` with a WAV body) of the recording after stop: no live text,
 * cheaper than streaming. The 2 GB file limit is far beyond a dictation, so it is one upload.
 */
class DeepgramFileTranscription(
    private val httpClient: OkHttpClient,
    private val apiKey: suspend () -> String?,
    private val url: String = LISTEN_URL,
    private val warmUpUrl: String = LISTEN_URL,
) : SpeechToTextProvider {

    /** `System.nanoTime()` of the last warm-up, or null. */
    @Volatile
    private var warmedUpAt: Long? = null

    override val streams: Boolean get() = false

    override fun sampleRate(model: String): Int = DEEPGRAM_SAMPLE_RATE

    override suspend fun transcribe(
        audio: Flow<ByteArray>,
        model: String,
        language: String,
        timeoutMs: Long,
        onPartial: (String) -> Unit,
    ): String {
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: throw SttException("Add your Deepgram API key in Iris settings")
        val chunks = ArrayList<ByteArray>()
        audio.collect { chunks += it }
        val stoppedAt = System.nanoTime()
        val wav = wavBytes(chunks, DEEPGRAM_SAMPLE_RATE)
        val body = SentBody(wav.toRequestBody("audio/wav".toMediaType()))
        val request = Request.Builder()
            .url(listenUrl(url, model, language, streaming = false))
            .header("Authorization", "Token $key")
            .post(body)
            .build()
        val audioMs = chunks.sumOf { it.size } * 1000L / (DEEPGRAM_SAMPLE_RATE * 2)
        val client = httpClient.newBuilder()
            .readTimeout(uploadReadTimeoutMs(timeoutMs, audioMs), MILLISECONDS)
            .build()
        val (code, response) = try {
            client.newCall(request).await()
        } catch (e: InterruptedIOException) {
            throw SttException(
                if (body.sent) "Timed out waiting for the transcript" else "Timed out sending audio to Deepgram"
            )
        } catch (e: IOException) {
            throw SttException(deepgramNetworkErrorMessage(e))
        }
        if (code !in 200..299) throw SttException(deepgramHttpErrorMessage(code, response, dgError = null))
        val text = parseListenResponse(response) ?: throw SttException("Unexpected response from Deepgram")
        val ms = (System.nanoTime() - stoppedAt) / 1_000_000
        Log.d(TAG, "Timing: upload ${wav.size / 1024} KB, stop->response $ms ms")
        return text.trim()
    }

    /**
     * `HEAD /v1/listen` without a key: a quick 405 that leaves an idle (HTTP/2) connection in the shared pool for the
     * upload. Skipped within [WARM_UP_WINDOW_MS] of an earlier warm-up (an HTTP/2 connection returns to the pool).
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
        const val TAG = "DeepgramFileStt"

        /** Inside OkHttp's 5 min pool keep-alive. */
        const val WARM_UP_WINDOW_MS = 180_000L
    }
}
