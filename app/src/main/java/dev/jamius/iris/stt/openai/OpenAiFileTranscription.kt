/*
* Author: Jamius Siam
* Since: 12/11/2026
*/

package dev.jamius.iris.stt.openai

import android.util.Log
import dev.jamius.iris.stt.SentBody
import dev.jamius.iris.stt.SpeechToTextProvider
import dev.jamius.iris.stt.SttException
import dev.jamius.iris.stt.WAV_HEADER_BYTES
import dev.jamius.iris.stt.await
import dev.jamius.iris.stt.splitAtPauses
import dev.jamius.iris.stt.uploadReadTimeoutMs
import dev.jamius.iris.stt.vercel.joined
import dev.jamius.iris.stt.wavBytes
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit.MILLISECONDS

/**
 * OpenAI file transcription (`POST /v1/audio/transcriptions`) for models outside the Realtime list: the recording is
 * uploaded as 16 kHz WAV after stop, with no live text. Recordings over the 25 MB file limit are split at pauses
 * into several uploads, sent one after another, and their texts joined.
 */
class OpenAiFileTranscription(
    private val httpClient: OkHttpClient,
    private val apiKey: suspend () -> String?,
    private val url: String = TRANSCRIPTIONS_URL,
    private val warmUpUrl: String = WARM_UP_URL,
    private val maxPartBytes: Int = MAX_PART_BYTES,
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
            ?: throw SttException("Add your OpenAI API key in Iris settings")
        val chunks = ArrayList<ByteArray>()
        audio.collect { chunks += it }
        val stoppedAt = System.nanoTime()
        val parts = splitAtPauses(chunks, maxPartBytes, SPLIT_SEARCH_BYTES)
        var text = ""
        for (part in parts) text = joined(text, upload(key, wavBytes(part, SAMPLE_RATE), model, language, timeoutMs))
        val kb = chunks.sumOf { it.size } / 1024
        Log.d(TAG, "Timing: upload $kb KB in ${parts.size} part(s), stop->response ${elapsedMs(stoppedAt)} ms")
        return text
    }

    /** Uploads one WAV file and returns its trimmed text. */
    private suspend fun upload(key: String, wav: ByteArray, model: String, language: String, timeoutMs: Long): String {
        val audioMs = (wav.size - WAV_HEADER_BYTES) * 1000L / (SAMPLE_RATE * 2)
        val client = httpClient.newBuilder()
            .readTimeout(uploadReadTimeoutMs(timeoutMs, audioMs), MILLISECONDS)
            .build()
        val body = SentBody(transcriptionForm(wav, model, language))
        val request = Request.Builder().url(url).header("Authorization", "Bearer $key").post(body).build()
        val (code, response) = try {
            client.newCall(request).await()
        } catch (e: InterruptedIOException) {
            throw SttException(if (body.sent) TRANSCRIPT_TIMEOUT else "Timed out sending audio to OpenAI")
        } catch (e: IOException) {
            throw SttException("Network error: ${e.message ?: e.javaClass.simpleName}")
        }
        if (code !in 200..299) throw SttException(openAiHttpErrorMessage(code, response))
        return parseTranscriptionText(response)?.trim() ?: throw SttException("Unexpected response from OpenAI")
    }

    /**
     * `HEAD /v1/models` without a key: a quick 401 that leaves an idle (HTTP/2) connection in the shared pool for the
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
        const val TAG = "OpenAiFileStt"
        const val SAMPLE_RATE = 16_000
        const val TRANSCRIPT_TIMEOUT = "Timed out waiting for the transcript"

        /** Under OpenAI's 25 MB file limit with room to spare: ~10 min of 16 kHz audio. */
        const val MAX_PART_BYTES = 20_000_000

        /** Where a cut may go: the quietest chunk of a part's last 30 s. */
        const val SPLIT_SEARCH_BYTES = 30 * SAMPLE_RATE * 2

        /** Inside OkHttp's 5 min pool keep-alive and api.openai.com's idle timeout. */
        const val WARM_UP_WINDOW_MS = 180_000L

        fun elapsedMs(since: Long): Long = (System.nanoTime() - since) / 1_000_000
    }
}

/** OpenAI's file transcription endpoint. */
internal const val TRANSCRIPTIONS_URL = "https://api.openai.com/v1/audio/transcriptions"

/**
 * The multipart form for one file: `file` (`audio.wav`), `model`, `response_format=json`, and the language when set:
 * `languages[]` for models that take the list, else `language`.
 */
internal fun transcriptionForm(wav: ByteArray, model: String, language: String): MultipartBody =
    MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
        .addFormDataPart("model", model)
        .addFormDataPart("response_format", "json")
        .apply {
            when {
                language.isBlank() -> Unit
                usesLanguageList(model) -> addFormDataPart("languages[]", language)
                else -> addFormDataPart("language", language)
            }
        }
        .build()

private val json = Json { ignoreUnknownKeys = true }

/** `text` of a `response_format=json` response, or null if the body isn't that shape. */
internal fun parseTranscriptionText(body: String): String? = try {
    ((json.parseToJsonElement(body) as? JsonObject)?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content
} catch (e: SerializationException) {
    null
}
