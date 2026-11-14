/*
* Author: Jamius Siam
* Since: 12/11/2026
*/

package dev.jamius.iris.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechGateTest {

    /** Speech = any non-zero sample. */
    private val model: (FloatArray) -> Float = { frame -> if (frame.any { it != 0f }) 1f else 0f }

    private fun gate(
        hangoverMs: Int = SpeechGate.STREAMING_HANGOVER_MS,
        rate: Int = 16_000,
        model: (FloatArray) -> Float = this.model,
        markSegmentEnds: Boolean = false,
    ) = SpeechGate(rate, hangoverMs, model, markSegmentEnds)

    /** One 32 ms frame at 16 kHz, tagged with [id] in its first sample (0 = silence). */
    private fun frame(id: Int = 0) = ByteArray(1024).also { it[0] = id.toByte() }

    @Test
    fun silenceIsNeverSent() {
        val gate = gate()
        repeat(100) { assertTrue(gate.process(frame()).isEmpty()) }
    }

    @Test
    fun aSingleVoicedFrameIsNotSpeechAndTheOnsetResetsOnSilence() {
        val gate = gate()
        repeat(10) {
            assertTrue(gate.process(frame()).isEmpty())
            assertTrue(gate.process(frame(1)).isEmpty())
        }
    }

    @Test
    fun onsetSendsThePreRollOldestFirstThenEachSpeechChunkAlone() {
        val gate = gate()
        val chunks = (0 until 20).map { ByteArray(1024) }
        chunks.forEach { assertTrue(gate.process(it).isEmpty()) }
        val first = frame(1)
        assertTrue(gate.process(first).isEmpty())
        val onset = frame(2)

        val sent = gate.process(onset)

        // 450 ms = 14.06 frames: the last 15 unsent chunks (the first voiced one included), then the onset chunk.
        assertEquals(16, sent.size)
        chunks.takeLast(14).forEachIndexed { i, chunk -> assertSame(chunk, sent[i]) }
        assertSame(first, sent[14])
        assertSame(onset, sent[15])
        val next = frame(3)
        assertEquals(listOf(next), gate.process(next))
    }

    @Test
    fun streamingHangoverSendsFiftyTwoFramesAfterTheLastVoicedOne() =
        assertHangover(SpeechGate.STREAMING_HANGOVER_MS, 52)

    @Test
    fun offlineHangoverSendsFifteenFramesAfterTheLastVoicedOne() =
        assertHangover(SpeechGate.OFFLINE_HANGOVER_MS, 15)

    private fun assertHangover(hangoverMs: Int, frames: Int) {
        val gate = gate(hangoverMs)
        gate.process(frame(1))
        assertEquals(2, gate.process(frame(1)).size)
        repeat(frames) { assertEquals(1, gate.process(frame()).size) }
        repeat(5) { assertTrue(gate.process(frame()).isEmpty()) }
    }

    @Test
    fun laterOnsetSendsOnlyChunksThatWerentSentYet() {
        val gate = gate(SpeechGate.OFFLINE_HANGOVER_MS)
        gate.process(frame(1))
        gate.process(frame(1))
        repeat(15) { gate.process(frame()) } // Hangover: all sent.
        val unsent = (0 until 3).map { ByteArray(1024) }
        unsent.forEach { assertTrue(gate.process(it).isEmpty()) }
        val first = frame(1)
        gate.process(first)
        val onset = frame(1)

        assertEquals(unsent + listOf(first, onset), gate.process(onset))
    }

    @Test
    fun speechAtTheStartIsSentFromTheOnsetWithWhateverPreRollExists() {
        val gate = gate()
        val first = frame(1)
        assertTrue(gate.process(first).isEmpty())
        val second = frame(1)
        assertEquals(listOf(first, second), gate.process(second))
    }

    @Test
    fun thresholdIsStrict() {
        val at = gate(model = { 0.3f })
        repeat(10) { assertTrue(at.process(frame()).isEmpty()) }
        val above = gate(model = { 0.31f })
        assertTrue(above.process(frame()).isEmpty())
        assertEquals(2, above.process(frame()).size)
    }

    @Test
    fun framesSpanChunkBoundariesAndAreScaled() {
        val frames = mutableListOf<FloatArray>()
        val gate = gate(model = { frames += it.copyOf(); 0f })
        // 50 ms chunks (800 samples) of a sample ramp 0, 1, 2, ...
        repeat(4) { chunk -> gate.process(ramp(chunk * 800, 800)) }

        assertEquals(6, frames.size) // 3200 samples = 6 whole frames, 128 carried.
        frames.forEachIndexed { f, frame ->
            assertArrayEquals(FloatArray(512) { (f * 512 + it) / 32768f }, frame, 0f)
        }
    }

    @Test
    fun chunksCompletingNoFrameFollowTheSpeechState() {
        val gate = gate()
        // 16 ms chunks: every other one completes a frame.
        val voiced = ByteArray(512) { 1 }
        assertTrue(gate.process(voiced).isEmpty())
        assertTrue(gate.process(voiced).isEmpty()) // Frame 1: onset 1.
        assertTrue(gate.process(voiced).isEmpty())
        assertEquals(4, gate.process(voiced).size) // Frame 2: onset, with the pre-roll.
        assertEquals(1, gate.process(voiced).size) // No frame completed, in speech.
    }

    @Test
    fun twentyFourKilohertzFramesAreDownsampledForTheModel() {
        val frames = mutableListOf<FloatArray>()
        val gate = gate(rate = 24_000, model = { frames += it.copyOf(); 0f })
        // 50 ms chunks at 24 kHz (1200 samples).
        repeat(2) { chunk -> gate.process(ramp(chunk * 1200, 1200)) }

        assertEquals(3, frames.size) // 2400 samples = 3 frames of 768, 96 carried.
        frames.forEachIndexed { f, frame ->
            val base = f * 768
            val expected = FloatArray(512) {
                val s = base + 3 * (it / 2)
                if (it % 2 == 0) s / 32768f else (2 * s + 3) / 65536f
            }
            assertArrayEquals(expected, frame, 1e-7f)
        }
    }

    @Test
    fun modelFailureFailsOpen() {
        var calls = 0
        val error = IllegalStateException("VAD model unavailable")
        val gate = gate(model = {
            calls++
            if (calls == 3) throw error
            0f
        })
        val silent = (0 until 2).map { ByteArray(1024) }
        silent.forEach { assertTrue(gate.process(it).isEmpty()) }
        val failing = frame()

        assertEquals(silent + listOf(failing), gate.process(failing))
        assertSame(error, gate.failure)
        val later = frame()
        assertEquals(listOf(later), gate.process(later))
        assertEquals(3, calls)
    }

    @Test
    fun segmentEndIsMarkedAloneWhenTheChunkIsWithheld() {
        val gate = gate(SpeechGate.OFFLINE_HANGOVER_MS, markSegmentEnds = true)
        gate.process(frame(1))
        assertEquals(2, gate.process(frame(1)).size)
        repeat(15) { assertEquals(1, gate.process(frame()).size) }

        val end = gate.process(frame())

        assertEquals(1, end.size)
        assertTrue(end[0].isEmpty())
        repeat(5) { assertTrue(gate.process(frame()).isEmpty()) }
    }

    @Test
    fun segmentEndFollowsTheLastKeptChunk() {
        val gate = gate(SpeechGate.OFFLINE_HANGOVER_MS, markSegmentEnds = true)
        // Two frames per chunk: frame 16 after the speech (the end) shares a chunk with the last hangover frame.
        val speech = ByteArray(2048).also {
            it[0] = 1
            it[1024] = 1
        }
        assertEquals(listOf(speech), gate.process(speech))
        repeat(7) { assertEquals(1, gate.process(ByteArray(2048)).size) }
        val last = ByteArray(2048)

        val sent = gate.process(last)

        assertEquals(2, sent.size)
        assertSame(last, sent[0])
        assertTrue(sent[1].isEmpty())
        assertTrue(gate.process(ByteArray(2048)).isEmpty())
    }

    @Test
    fun noSegmentEndWithoutTheFlag() {
        val gate = gate(SpeechGate.OFFLINE_HANGOVER_MS)
        gate.process(frame(1))
        gate.process(frame(1))
        val sent = (0 until 30).flatMap { gate.process(frame()) }
        assertEquals(15, sent.size)
        assertTrue(sent.none { it.isEmpty() })
    }

    @Test
    fun hangoverFramesRoundUpLikeHandy() {
        assertEquals(52, SpeechGate.framesFor(SpeechGate.STREAMING_HANGOVER_MS))
        assertEquals(15, SpeechGate.framesFor(SpeechGate.OFFLINE_HANGOVER_MS))
        assertEquals(15, SpeechGate.framesFor(SpeechGate.PRE_ROLL_MS))
    }

    /** [count] little-endian PCM16 samples counting up from [start]. */
    private fun ramp(start: Int, count: Int) = ByteArray(count * 2).also {
        for (i in 0 until count) {
            val s = start + i
            it[2 * i] = s.toByte()
            it[2 * i + 1] = (s shr 8).toByte()
        }
    }
}
