/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.pow

class AudioLevelTest {

    /** [count] little-endian PCM16 samples alternating +[amplitude] / -[amplitude] (constant RMS). */
    private fun samples(amplitude: Int, count: Int = 100) = ByteArray(2 * count).also {
        for (i in 0 until count) {
            val sample = if (i % 2 == 0) amplitude else -amplitude
            it[2 * i] = sample.toByte()
            it[2 * i + 1] = (sample shr 8).toByte()
        }
    }

    @Test
    fun emptyOrSilentIsZero() {
        assertEquals(0f, pcm16Level(ByteArray(0)))
        assertEquals(0f, pcm16Level(ByteArray(200)))
    }

    @Test
    fun fullScaleIsOne() {
        assertEquals(1f, pcm16Level(samples(32767)), 0.0001f)
    }

    @Test
    fun belowTheFloorIsZero() {
        assertEquals(0f, pcm16Level(samples(50))) // About -56 dBFS.
    }

    @Test
    fun midLevelIsEased() {
        // 690 is about -33.5 dBFS: halfway between -55 and -12, eased by pow 0.7.
        assertEquals(0.5f.pow(0.7f), pcm16Level(samples(690)), 0.01f)
    }

    @Test
    fun lengthLimitsTheSamplesRead() {
        val bytes = ByteArray(400).also { samples(32767).copyInto(it) }
        assertEquals(1f, pcm16Level(bytes, 200), 0.0001f)
        assertEquals(0f, pcm16Level(bytes.copyOfRange(200, 400)))
    }

    @Test
    fun trailingOddByteIsIgnored() {
        val bytes = ByteArray(3).also { it[2] = 0x7F }
        assertEquals(0f, pcm16Level(bytes))
    }
}
