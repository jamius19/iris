/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.stt.ondevice

import android.os.SystemClock
import android.util.Log
import dev.jamius.iris.stt.SpeechToTextProvider
import dev.jamius.iris.stt.SttException
import dev.jamius.iris.stt.splitAtPauses
import dev.jamius.iris.stt.vercel.joined
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Model input per decode at most (Parakeet: 15-20 s chunks; Whisper truncates > ~29.5 s). */
internal const val MAX_SEGMENT_BYTES = 20 * 16_000 * 2

/** How far back from the cap a long segment's cut looks for a pause. */
private const val SPLIT_SEARCH_BYTES = 8 * 16_000 * 2

/**
 * On-device transcription with a downloaded sherpa-onnx model (offline recognizers): each speech segment (ended by
 * the VAD gate's empty arrays, or cut at a pause past [maxSegmentBytes]) is decoded while recording continues, and
 * the joined text so far goes to `onPartial`; the rest is decoded after stop. No network: `timeoutMs` is ignored.
 *
 * @param decode the text of a segment; serialized and cancellable (the recognizer's).
 * @param selection the stored (model id, language) for [warmUp].
 * @param scope where [warmUp] reads [selection] (the app scope).
 */
class OnDeviceSpeechToText(
    private val isDownloaded: (String) -> Boolean,
    private val preload: (OnDeviceModel, language: String) -> Unit,
    private val decode: suspend (OnDeviceModel, language: String, FloatArray) -> String,
    private val selection: suspend () -> Pair<String, String>,
    private val scope: CoroutineScope,
    private val maxSegmentBytes: Int = MAX_SEGMENT_BYTES,
) : SpeechToTextProvider {

    override fun sampleRate(model: String): Int = SAMPLE_RATE

    override val streams: Boolean get() = false

    // Segments are decoded in isolation: the long hangover keeps whole phrases together.
    override fun liveText(model: String): Boolean = true

    override val wantsSegmentEnds: Boolean get() = true

    override suspend fun transcribe(
        audio: Flow<ByteArray>,
        model: String,
        language: String,
        timeoutMs: Long,
        onPartial: (String) -> Unit,
    ): String {
        val m = onDeviceModel(model)
        // Before collecting: the mic never opens without the model.
        if (!isDownloaded(m.id)) throw SttException("Download the ${m.label} model in Iris settings")
        val lang = languageFor(m, language)
        preload(m, lang)
        return coroutineScope {
            val segments = Channel<List<ByteArray>>(Channel.UNLIMITED)
            var text = ""
            var count = 0
            // Queued and buffered audio not decoded yet, for the deadline after stop.
            var pendingBytes = 0L
            // Never makes the collector wait (the mic would overflow): segments queue up.
            val decoder = launch {
                for (segment in segments) {
                    val part = decode(m, lang, pcm16ToFloats(segment)).trim()
                    pendingBytes -= segment.sumOf { it.size }
                    count++
                    if (part.isNotEmpty()) {
                        text = joined(text, part)
                        onPartial(text)
                    }
                }
            }
            fun queue(segment: List<ByteArray>) {
                if (segment.isEmpty()) return
                segments.trySend(segment)
            }
            val buffer = ArrayList<ByteArray>()
            var bufferBytes = 0
            audio.collect { chunk ->
                if (chunk.isEmpty()) {
                    queue(buffer.toList())
                    buffer.clear()
                    bufferBytes = 0
                    return@collect
                }
                buffer += chunk
                bufferBytes += chunk.size
                pendingBytes += chunk.size
                if (bufferBytes > maxSegmentBytes) {
                    val parts = splitAtPauses(buffer, maxSegmentBytes, SPLIT_SEARCH_BYTES).map { it.toList() }
                    parts.dropLast(1).forEach(::queue)
                    buffer.clear()
                    buffer += parts.last()
                    bufferBytes = buffer.sumOf { it.size }
                }
            }
            val stoppedAt = SystemClock.elapsedRealtime()
            queue(buffer.toList())
            segments.close()
            // No network to stall and a native decode can't be aborted: only a safety net for a hung decode (covers a
            // model load after stop plus decoding at RTF <= 2).
            val pendingMs = pendingBytes * 1000 / (SAMPLE_RATE * 2)
            withTimeoutOrNull(BASE_DEADLINE_MS + 2 * pendingMs) { decoder.join() }
                ?: throw SttException("On-device transcription timed out")
            Log.d(TAG, "Timing: stop->final ${SystemClock.elapsedRealtime() - stoppedAt} ms, $count segments")
            text
        }
    }

    override fun warmUp() {
        scope.launch {
            val (id, language) = selection()
            val m = onDeviceModel(id)
            if (isDownloaded(m.id)) preload(m, languageFor(m, language))
        }
    }

    /** Only Whisper takes a language, and only one it knows. */
    private fun languageFor(model: OnDeviceModel, language: String): String =
        if (model.engine == OnDeviceEngine.WHISPER) whisperLanguage(language) else ""

    private companion object {
        const val TAG = "OnDeviceSpeechToText"
        const val SAMPLE_RATE = 16_000
        const val BASE_DEADLINE_MS = 60_000L
    }
}

/** Little-endian PCM16 [chunks] as samples in -1..1. */
internal fun pcm16ToFloats(chunks: List<ByteArray>): FloatArray {
    val samples = FloatArray(chunks.sumOf { it.size } / 2)
    var i = 0
    for (chunk in chunks) {
        for (j in 0 until chunk.size / 2) {
            samples[i++] = ((chunk[2 * j].toInt() and 0xFF) or (chunk[2 * j + 1].toInt() shl 8)) / 32768f
        }
    }
    return samples
}
