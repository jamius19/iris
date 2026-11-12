/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.stt.soniox

import android.util.Log
import dev.jamius.iris.stt.SentBody
import dev.jamius.iris.stt.SpeechToTextProvider
import dev.jamius.iris.stt.SttException
import dev.jamius.iris.stt.await
import dev.jamius.iris.stt.uploadReadTimeoutMs
import dev.jamius.iris.stt.wavBytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
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
 * Soniox async transcription of the recording after stop: upload the WAV (`POST /v1/files`), create a job
 * (`POST /v1/transcriptions`), poll it, fetch its transcript. The job and the file are deleted afterwards in every
 * case (they count against Soniox's quotas for 30 days otherwise).
 *
 * @param modelInfo the model's catalogue entry (null if unknown), for the strict-hints check.
 */
class SonioxFileTranscription(
    private val httpClient: OkHttpClient,
    private val apiKey: suspend () -> String?,
    private val modelInfo: suspend (String) -> SonioxModel?,
    private val baseUrl: String = SonioxRegion.US.restUrl,
    private val warmUpUrl: String = "$baseUrl/$SONIOX_MODELS_PATH",
) : SpeechToTextProvider {

    /** `System.nanoTime()` of the last warm-up, or null. */
    @Volatile
    private var warmedUpAt: Long? = null

    override val streams: Boolean get() = false

    override fun sampleRate(model: String): Int = SONIOX_SAMPLE_RATE

    override suspend fun transcribe(
        audio: Flow<ByteArray>,
        model: String,
        language: String,
        timeoutMs: Long,
        onPartial: (String) -> Unit,
    ): String {
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: throw SttException("Add your Soniox API key in Iris settings")
        val chunks = ArrayList<ByteArray>()
        audio.collect { chunks += it }
        val stoppedAt = System.nanoTime()
        val hints = sonioxLanguageHints(language)
        val strict = hints.isNotEmpty() && modelInfo(model)?.supportsLanguageHintsStrict == true
        val wav = wavBytes(chunks, SONIOX_SAMPLE_RATE)
        val audioMs = chunks.sumOf { it.size } * 1000L / (SONIOX_SAMPLE_RATE * 2)
        val budget = uploadReadTimeoutMs(timeoutMs, audioMs)
        val client = httpClient.newBuilder().readTimeout(budget, MILLISECONDS).build()
        var fileId: String? = null
        var jobId: String? = null
        try {
            val file = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
                .build()
            val upload = SentBody(file)
            val uploaded = client.fetch(request(key, url("v1/files")).post(upload).build(), upload)
            val uploadedId = parseSonioxId(uploaded) ?: throw SttException(UNEXPECTED)
            fileId = uploadedId
            val create = sonioxCreateTranscriptionBody(model, uploadedId, hints, strict)
                .toRequestBody("application/json".toMediaType())
            val created = client.fetch(request(key, url("v1/transcriptions")).post(create).build())
            val id = parseSonioxId(created) ?: throw SttException(UNEXPECTED)
            jobId = id
            var polls = 0
            val text = withTimeoutOrNull(budget) {
                var wait = FIRST_POLL_MS
                while (true) {
                    delay(wait)
                    wait = minOf(wait * 2, MAX_POLL_MS)
                    polls++
                    val job = parseSonioxJob(client.fetch(request(key, url("v1/transcriptions", id)).build()))
                        ?: throw SttException(UNEXPECTED)
                    if (job.status == "completed") break
                    if (isSonioxJobFailed(job.status)) {
                        throw SttException("Soniox error: ${job.errorMessage ?: "transcription failed"}")
                    }
                }
                val transcript = client.fetch(request(key, url("v1/transcriptions", id, "transcript")).build())
                parseSonioxTranscriptText(transcript) ?: throw SttException(UNEXPECTED)
            } ?: throw SttException(TRANSCRIPT_TIMEOUT)
            val ms = (System.nanoTime() - stoppedAt) / 1_000_000
            Log.d(TAG, "Timing: upload ${wav.size / 1024} KB, stop->transcript $ms ms, $polls polls")
            return text.trim()
        } finally {
            // Success, failure, timeout or cancellation: never suspends, so it also runs when cancelled.
            jobId?.let { delete(key, url("v1/transcriptions", it)) }
            fileId?.let { delete(key, url("v1/files", it)) }
        }
    }

    /**
     * `HEAD /v1/models` without a key: a quick 405 that leaves an idle connection in the shared pool for the upload.
     * Skipped within [WARM_UP_WINDOW_MS] of an earlier warm-up (the connection returns to the pool).
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

    private fun url(path: String, vararg ids: String): HttpUrl =
        baseUrl.toHttpUrl().newBuilder().addPathSegments(path).apply { ids.forEach { addPathSegment(it) } }.build()

    private fun request(key: String, url: HttpUrl): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $key")

    /**
     * The 2xx response's body. A timeout is the upload's ([upload] not fully written yet) or the transcript's.
     * Cancelling the coroutine cancels the call.
     */
    private suspend fun OkHttpClient.fetch(request: Request, upload: SentBody? = null): String {
        val (code, body) = try {
            newCall(request).await()
        } catch (e: InterruptedIOException) {
            throw SttException(if (upload?.sent == false) "Timed out sending audio to Soniox" else TRANSCRIPT_TIMEOUT)
        } catch (e: IOException) {
            throw SttException(sonioxNetworkErrorMessage(e))
        }
        if (code !in 200..299) throw SttException(sonioxHttpErrorMessage(code, body))
        return body
    }

    /**
     * Fire-and-forget `DELETE` on the base client: outlives a cancelled session. A failure is only logged (a job
     * still processing answers 409 and is left to Soniox's 30-day auto-delete).
     */
    private fun delete(key: String, url: HttpUrl) {
        httpClient.newCall(request(key, url).delete().build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d(TAG, "Delete ${url.encodedPath} failed: $e")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { if (!it.isSuccessful) Log.d(TAG, "Delete ${url.encodedPath}: ${it.code}") }
            }
        })
    }

    private companion object {
        const val TAG = "SonioxFileStt"
        const val UNEXPECTED = "Unexpected response from Soniox"
        const val TRANSCRIPT_TIMEOUT = "Timed out waiting for the transcript"

        /** Polls after 250, 500, then every 1000 ms: short dictations finish fast, long ones don't hammer the API. */
        const val FIRST_POLL_MS = 250L
        const val MAX_POLL_MS = 1_000L

        /** Inside OkHttp's 5 min pool keep-alive; api.soniox.com's idle timeout is unmeasured. */
        const val WARM_UP_WINDOW_MS = 180_000L
    }
}
