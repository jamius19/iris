/*
* Author: Jamius Siam
* Since: 12/11/2026
*/

package dev.jamius.iris.stt

import dev.jamius.iris.audio.pcm16Level
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Helpers for the providers that upload the recording after stop.

/** Size of the RIFF/WAVE header [wavBytes] writes. */
internal const val WAV_HEADER_BYTES = 44

/**
 * A PCM16 mono WAV file of [chunks] at [sampleRate]. Audio under 1 s is padded with silence to 1.25 s, as Handy does
 * (some services reject very short files).
 */
internal fun wavBytes(chunks: List<ByteArray>, sampleRate: Int): ByteArray {
    val bytesPerSecond = sampleRate * 2
    val audioBytes = chunks.sumOf { it.size }
    val dataBytes = if (audioBytes < bytesPerSecond) bytesPerSecond * 5 / 4 else audioBytes
    val wav = ByteBuffer.allocate(WAV_HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
    wav.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
    wav.put("fmt ".toByteArray()).putInt(16)
        .putShort(1.toShort()) // PCM
        .putShort(1.toShort()) // Mono
        .putInt(sampleRate)
        .putInt(bytesPerSecond)
        .putShort(2.toShort()) // Block align
        .putShort(16.toShort()) // Bits per sample
    wav.put("data".toByteArray()).putInt(dataBytes)
    chunks.forEach { wav.put(it) }
    return wav.array() // The padding is the buffer's zeros.
}

/**
 * How long an upload may wait for the response once it is sent: the STT timeout setting ([timeoutMs]) plus a quarter
 * of the audio's duration ([audioMs]), as transcribing takes longer the more audio there is.
 */
internal fun uploadReadTimeoutMs(timeoutMs: Long, audioMs: Long): Long = timeoutMs + audioMs / 4

/**
 * Splits [chunks] into parts of at most [maxBytes] each (a single larger chunk is a part of its own), so each fits an
 * upload size limit. Each cut is after the quietest chunk within the last [searchBytes] of the part (the latest one
 * of equally quiet chunks), so it lands in a pause rather than mid-word: with VAD on, the joins between kept
 * segments are such pauses (hangover plus pre-roll of non-speech).
 */
internal fun splitAtPauses(chunks: List<ByteArray>, maxBytes: Int, searchBytes: Int): List<List<ByteArray>> {
    val parts = ArrayList<List<ByteArray>>()
    var start = 0
    while (start < chunks.size) {
        // The longest part from `start` that fits.
        var end = start
        var size = 0
        while (end < chunks.size && (end == start || size + chunks[end].size <= maxBytes)) size += chunks[end++].size
        if (end == chunks.size) {
            parts += chunks.subList(start, end)
            break
        }
        // Back up within the search window to the quietest chunk; the part ends after it.
        var cut = end
        var quietest = Float.MAX_VALUE
        var candidate = end
        var tail = 0
        while (candidate > start && tail < searchBytes) {
            val level = pcm16Level(chunks[candidate - 1])
            if (level < quietest) {
                quietest = level
                cut = candidate
            }
            tail += chunks[candidate - 1].size
            candidate--
        }
        parts += chunks.subList(start, cut)
        start = cut
    }
    return parts
}

/** Enqueues the call and returns its HTTP code and body. Cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Pair<Int, String> = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = try {
                response.use { it.code to it.body.string() }
            } catch (e: IOException) {
                cont.resumeWithException(e)
                return
            }
            cont.resume(result)
        }
    })
}

/** [delegate], recording when it has been written in full: a timeout after that is the server's. */
internal class SentBody(private val delegate: RequestBody) : RequestBody() {
    @Volatile
    var sent = false
        private set

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        sent = false
        delegate.writeTo(sink)
        sent = true
    }
}
