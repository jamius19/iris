/*
* Author: Jamius Siam
* Since: 12/11/2026
*/

package dev.jamius.iris.stt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {

    @Test
    fun headerDescribesPcm16MonoAndChunksFollowInOrder() {
        val a = ByteArray(16_000) { 1 }
        val b = ByteArray(16_002) { 2 }

        val wav = wavBytes(listOf(a, b), 16_000)

        val data = 32_002
        assertEquals(WAV_HEADER_BYTES + data, wav.size)
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(36 + data, header.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("fmt ", String(wav, 12, 4))
        assertEquals(16, header.getInt(16))
        assertEquals(1.toShort(), header.getShort(20)) // PCM
        assertEquals(1.toShort(), header.getShort(22)) // Mono
        assertEquals(16_000, header.getInt(24))
        assertEquals(32_000, header.getInt(28))
        assertEquals(2.toShort(), header.getShort(32))
        assertEquals(16.toShort(), header.getShort(34))
        assertEquals("data", String(wav, 36, 4))
        assertEquals(data, header.getInt(40))
        assertArrayEquals(a + b, wav.copyOfRange(WAV_HEADER_BYTES, wav.size))
    }

    @Test
    fun audioUnderOneSecondIsPaddedToOneAndAQuarterSeconds() {
        val half = ByteArray(16_000) { 3 } // 0.5 s at 16 kHz.

        val wav = wavBytes(listOf(half), 16_000)

        assertEquals(WAV_HEADER_BYTES + 40_000, wav.size)
        assertEquals(40_000, ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
        assertArrayEquals(half, wav.copyOfRange(WAV_HEADER_BYTES, WAV_HEADER_BYTES + 16_000))
        assertTrue(wav.copyOfRange(WAV_HEADER_BYTES + 16_000, wav.size).all { it == 0.toByte() })
    }

    @Test
    fun oneSecondOrMoreIsNotPadded() {
        assertEquals(WAV_HEADER_BYTES + 32_000, wavBytes(listOf(ByteArray(32_000)), 16_000).size)
        assertEquals(WAV_HEADER_BYTES + 96_000, wavBytes(listOf(ByteArray(96_000)), 24_000).size)
    }

    @Test
    fun uploadTimeoutAddsAQuarterOfTheAudio() {
        assertEquals(10_000L, uploadReadTimeoutMs(10_000, 0))
        assertEquals(25_000L, uploadReadTimeoutMs(10_000, 60_000))
    }

    @Test
    fun audioWithinTheLimitIsOnePart() {
        val chunks = List(10) { loud() }

        val parts = splitAtPauses(chunks, maxBytes = 10 * CHUNK, searchBytes = 4 * CHUNK)

        assertEquals(listOf(chunks), parts)
    }

    @Test
    fun cutsAfterTheQuietestChunkInTheSearchWindow() {
        val chunks = List(25) { if (it == 7) quiet() else loud() }

        val parts = splitAtPauses(chunks, maxBytes = 10 * CHUNK, searchBytes = 4 * CHUNK)

        // Part 1 ends after the quiet chunk 7; the rest has no pause in its windows, so it is cut at the limit.
        assertEquals(listOf(8, 10, 7), parts.map { it.size })
        assertSame(chunks[7], parts[0].last())
        assertEquals(chunks, parts.flatten())
    }

    @Test
    fun quietChunkOutsideTheWindowIsNotUsed() {
        val chunks = List(15) { if (it == 2) quiet() else loud() }

        val parts = splitAtPauses(chunks, maxBytes = 10 * CHUNK, searchBytes = 4 * CHUNK)

        assertEquals(listOf(10, 5), parts.map { it.size })
    }

    @Test
    fun equallyQuietChunksCutAtTheLatest() {
        val chunks = List(15) { quiet() }

        val parts = splitAtPauses(chunks, maxBytes = 10 * CHUNK, searchBytes = 4 * CHUNK)

        assertEquals(listOf(10, 5), parts.map { it.size })
        parts.forEach { assertTrue(it.sumOf { chunk -> chunk.size } <= 10 * CHUNK) }
    }

    /** A loud 50 ms chunk at 16 kHz: every sample 0x4000. */
    private fun loud() = ByteArray(CHUNK) { (if (it % 2 == 1) 0x40 else 0).toByte() }

    private fun quiet() = ByteArray(CHUNK)

    private companion object {
        const val CHUNK = 1_600
    }
}
