/*
* Author: Jamius Siam
* Since: 12/11/2026
*/

package dev.jamius.iris.audio

import dev.jamius.iris.stt.SttException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class TimeStretchTest {

    /** [seconds] of a voice-like 150 Hz harmonic signal at [rate], as PCM16 LE in 50 ms chunks. */
    private fun voice(rate: Int, seconds: Int = 5): List<ByteArray> =
        pcm(rate, seconds) { t -> (1..5).sumOf { h -> sin(2 * PI * 150 * h * t) / h } * 6_000 }

    /** [seconds] of a 200 Hz sine at [rate], as PCM16 LE in 50 ms chunks. */
    private fun sine(rate: Int, seconds: Int = 2): List<ByteArray> =
        pcm(rate, seconds) { t -> sin(2 * PI * 200 * t) * 10_000 }

    private fun pcm(rate: Int, seconds: Int, signal: (Double) -> Double): List<ByteArray> {
        val chunkSamples = rate / 20
        return (0 until seconds * 20).map { c ->
            ByteArray(chunkSamples * 2).also {
                for (i in 0 until chunkSamples) {
                    val sample = signal((c * chunkSamples + i).toDouble() / rate).roundToInt()
                    it[2 * i] = sample.toByte()
                    it[2 * i + 1] = (sample shr 8).toByte()
                }
            }
        }
    }

    private fun samples(bytes: ByteArray) = ShortArray(bytes.size / 2) {
        ((bytes[2 * it].toInt() and 0xFF) or (bytes[2 * it + 1].toInt() shl 8)).toShort()
    }

    private fun stretch(chunks: List<ByteArray>, rate: Int, speed: Float): List<ByteArray> = runBlocking {
        chunks.asFlow().timeStretched(rate, speed).toList()
    }

    @Test
    fun outputLengthIsInputDividedBySpeed() {
        for (rate in listOf(16_000, 24_000)) {
            for (speed in listOf(1.5f, 2.0f)) {
                val input = voice(rate)
                val inBytes = input.sumOf { it.size }
                val outBytes = stretch(input, rate, speed).sumOf { it.size }
                val expected = inBytes / speed
                assertTrue("$rate Hz ${speed}x: $outBytes of $inBytes", abs(outBytes - expected) <= expected * 0.01)
            }
        }
    }

    @Test
    fun theTailIsFlushedAfterUpstreamCompletes() = runBlocking {
        val input = voice(16_000, seconds = 1)
        var upstreamDone = false
        val afterDone = mutableListOf<ByteArray>()
        val all = mutableListOf<ByteArray>()
        flow {
            input.forEach { emit(it) }
            upstreamDone = true
        }.timeStretched(16_000, 2f).collect {
            all += it
            if (upstreamDone) afterDone += it
        }

        assertTrue(afterDone.isNotEmpty())
        assertTrue(afterDone.all { it.isNotEmpty() })
        val expected = input.sumOf { it.size } / 2.0
        assertTrue(abs(all.sumOf { it.size } - expected) <= expected * 0.01)
    }

    @Test
    fun upstreamErrorPropagatesUnchangedWithoutFlush() = runBlocking {
        val error = SttException("No speech detected")
        var thrown = false
        var afterError = 0
        val upstream: Flow<ByteArray> = flow {
            voice(16_000, seconds = 1).forEach { emit(it) }
            thrown = true
            throw error
        }
        try {
            upstream.timeStretched(16_000, 2f).collect { if (thrown) afterError++ }
            fail("No exception")
        } catch (e: SttException) {
            assertSame(error, e)
        }
        assertEquals(0, afterError)
    }

    @Test
    fun neverEmitsEmptyArrays() {
        val input = listOf(ByteArray(2)) + voice(24_000, seconds = 1)

        val output = stretch(input, 24_000, 1.1f)

        assertTrue(output.isNotEmpty())
        assertTrue(output.none { it.isEmpty() })
    }

    @Test
    fun pitchIsKept() {
        val input = samples(sine(16_000).reduce(ByteArray::plus))
        val output = samples(stretch(sine(16_000), 16_000, 2f).reduce(ByteArray::plus))

        // Zero crossings per sample: 400 per second at 200 Hz.
        fun crossingRate(s: ShortArray) = (1 until s.size).count { (s[it - 1] < 0) != (s[it] < 0) }.toDouble() / s.size
        val expected = crossingRate(input)
        assertEquals(400.0 / 16_000, expected, 0.001)
        assertEquals(expected, crossingRate(output), expected * 0.05)
    }
}
