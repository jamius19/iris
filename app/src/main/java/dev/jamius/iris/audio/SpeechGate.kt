/*
* Author: Jamius Siam
* Since: 12/11/2026
*/

package dev.jamius.iris.audio

/**
 * Voice activity gate for one recording (Handy's SmoothedVad): only audio around speech is sent. Splits PCM16 mono
 * chunks at [sampleRate] (16 or 24 kHz) into 32 ms frames, scores each with [speechProbability] ([VAD_FRAME_SAMPLES]
 * samples at 16 kHz, -1..1; 24 kHz is downsampled 3:2 first) and keeps frames once [ONSET_FRAMES] in a row score
 * above [THRESHOLD], until [hangoverMs] after the last voiced one. A chunk is sent whole if a frame completed in it
 * is kept; the first kept chunk also sends the unsent chunks of the last [PRE_ROLL_MS]. If scoring throws, fails
 * open: [failure] is set and every chunk is sent from then on.
 */
internal class SpeechGate(
    private val sampleRate: Int,
    hangoverMs: Int,
    private val speechProbability: (FloatArray) -> Float,
) {
    init {
        require(sampleRate == 16_000 || sampleRate == 24_000) { "Unsupported rate $sampleRate" }
    }

    var failure: Exception? = null
        private set

    private val frameBytes = sampleRate * 2 * VAD_FRAME_SAMPLES / VAD_RATE
    private val preRollBytes = sampleRate * 2 * PRE_ROLL_MS / 1000
    private val hangoverFrames = framesFor(hangoverMs)
    private val samples = FloatArray(VAD_FRAME_SAMPLES)

    /** Bytes of an incomplete frame, carried to the next chunk. */
    private var pending = ByteArray(0)
    private val preRoll = ArrayDeque<ByteArray>()
    private var preRollSize = 0
    private var inSpeech = false
    private var onset = 0
    private var hangover = 0

    /** The bytes to send for [chunk], in order: none, `[chunk]`, or the pre-roll followed by [chunk]. */
    fun process(chunk: ByteArray): List<ByteArray> {
        if (failure != null) return listOf(chunk)
        val kept = try {
            keepsChunk(chunk)
        } catch (e: Exception) {
            failure = e
            true
        }
        if (!kept) {
            preRoll.addLast(chunk)
            preRollSize += chunk.size
            // Drop the oldest chunks while the rest still cover the pre-roll.
            while (preRollSize - preRoll.first().size >= preRollBytes) preRollSize -= preRoll.removeFirst().size
            return emptyList()
        }
        val out = preRoll.toMutableList().apply { add(chunk) }
        preRoll.clear()
        preRollSize = 0
        return out
    }

    /** Runs every frame [chunk] completes; true if one of them is kept (or none completed while in speech). */
    private fun keepsChunk(chunk: ByteArray): Boolean {
        val data = pending + chunk
        var offset = 0
        var completed = false
        var kept = false
        while (data.size - offset >= frameBytes) {
            completed = true
            if (keepsFrame(speechProbability(frame(data, offset)) > THRESHOLD)) kept = true
            offset += frameBytes
        }
        pending = data.copyOfRange(offset, data.size)
        return kept || (!completed && inSpeech)
    }

    /** Handy's `SmoothedVad.push_frame`: whether a frame is sent. */
    private fun keepsFrame(voiced: Boolean): Boolean = when {
        !inSpeech && voiced -> {
            onset++
            if (onset >= ONSET_FRAMES) {
                inSpeech = true
                hangover = hangoverFrames
                onset = 0
                true
            } else {
                false
            }
        }
        inSpeech && voiced -> {
            hangover = hangoverFrames
            true
        }
        inSpeech -> if (hangover > 0) {
            hangover--
            true
        } else {
            inSpeech = false
            false
        }
        else -> {
            onset = 0
            false
        }
    }

    /** The frame at [offset] as 16 kHz samples, -1..1; 24 kHz keeps `s0` and averages `s1, s2` of every three. */
    private fun frame(data: ByteArray, offset: Int): FloatArray {
        if (sampleRate == VAD_RATE) {
            for (i in 0 until VAD_FRAME_SAMPLES) samples[i] = sample(data, offset, i) / 32768f
        } else {
            for (j in 0 until VAD_FRAME_SAMPLES / 2) {
                samples[2 * j] = sample(data, offset, 3 * j) / 32768f
                samples[2 * j + 1] = (sample(data, offset, 3 * j + 1) + sample(data, offset, 3 * j + 2)) / 65536f
            }
        }
        return samples
    }

    /** Little-endian PCM16 sample [index] of the frame at [offset]. */
    private fun sample(data: ByteArray, offset: Int, index: Int): Int {
        val i = offset + 2 * index
        return (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
    }

    companion object {
        // Handy's VAD parameters (src-tauri/src/audio_toolkit/vad/mod.rs, Silero with a 0.3 threshold).
        const val THRESHOLD = 0.3f
        const val ONSET_FRAMES = 2
        const val PRE_ROLL_MS = 450

        /** Hangover for providers that stream while recording: the server's own segmentation needs more tail. */
        const val STREAMING_HANGOVER_MS = 1650

        /** Hangover for providers that upload after stop. */
        const val OFFLINE_HANGOVER_MS = 450

        private const val VAD_RATE = 16_000
        private const val VAD_FRAME_SAMPLES = 512

        /** Whole VAD frames covering [ms], rounded up (Handy's `frames_for_duration_ms`). */
        fun framesFor(ms: Int): Int = (ms * VAD_RATE + VAD_FRAME_SAMPLES * 1000 - 1) / (VAD_FRAME_SAMPLES * 1000)
    }
}
