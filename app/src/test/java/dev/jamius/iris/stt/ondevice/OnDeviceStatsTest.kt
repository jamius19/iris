/*
* Author: Jamius Siam
* Since: 14/11/2026
*/

package dev.jamius.iris.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceStatsTest {

    private fun sample(speechMs: Long = 1_000, decodeMs: Long = 100, waitMs: Long = 200) =
        StatsSample(speechMs, decodeMs, waitMs)

    private fun waits(vararg ms: Long) = ModelStats(ms.map { sample(waitMs = it) })

    @Test
    fun withSampleAppendsAndLeavesTheRestAlone() {
        val other = ModelStats(listOf(sample(speechMs = 5)), lastLoadMs = 7)
        val before = mapOf(A to ModelStats(listOf(sample(speechMs = 1)), lastLoadMs = 4_800), B to other)

        val after = before.withSample(A, sample(speechMs = 2))

        assertEquals(listOf(1L, 2L), after.getValue(A).samples.map { it.speechMs })
        assertEquals(4_800L, after.getValue(A).lastLoadMs)
        assertEquals(other, after.getValue(B))
        val created = emptyMap<String, ModelStats>().withSample(A, sample(speechMs = 3))
        assertEquals(listOf(3L), created.getValue(A).samples.map { it.speechMs })
    }

    @Test
    fun theTwentyFirstSampleDropsTheOldest() {
        var stats = emptyMap<String, ModelStats>()
        for (i in 1..21) stats = stats.withSample(A, sample(speechMs = i.toLong()))

        assertEquals((2L..21L).toList(), stats.getValue(A).samples.map { it.speechMs })
    }

    @Test
    fun withLoadReplacesKeepsSamplesAndCreates() {
        val before = mapOf(A to ModelStats(listOf(sample()), lastLoadMs = 100))

        val after = before.withLoad(A, 200).withLoad(B, 300)

        assertEquals(ModelStats(listOf(sample()), lastLoadMs = 200), after.getValue(A))
        assertEquals(ModelStats(lastLoadMs = 300), after.getValue(B))
    }

    @Test
    fun speedFactorIsTheRatioOfSums() {
        // A phone dictation: 22,850 ms of speech decoded in 3,219 ms.
        assertEquals(7.098, ModelStats(listOf(sample(22_850, 3_219))).speedFactor()!!, 0.001)
        // (1,000 + 9,000) / (1,000 + 1,000), not the mean of 1 and 9.
        assertEquals(5.0, ModelStats(listOf(sample(1_000, 1_000), sample(9_000, 1_000))).speedFactor()!!, 0.0)
        assertNull(ModelStats().speedFactor())
    }

    @Test
    fun medians() {
        assertNull(median(emptyList()))
        assertEquals(300L, median(listOf(500, 100, 300)))
        assertEquals(250L, median(listOf(400, 100, 300, 200)))
    }

    @Test
    fun medianWait() {
        assertEquals(300L, waits(500, 100, 300).medianWaitMs())
        assertEquals(250L, waits(400, 100, 300, 200).medianWaitMs())
        assertNull(ModelStats().medianWaitMs())
    }

    @Test
    fun verdicts() {
        // On the factor rounded to one decimal, like the text.
        assertEquals(SpeedVerdict.KEEPS_UP, speedVerdict(1.5))
        assertEquals(SpeedVerdict.KEEPS_UP, speedVerdict(1.45))
        assertEquals(SpeedVerdict.BORDERLINE, speedVerdict(1.44))
        assertEquals(SpeedVerdict.BORDERLINE, speedVerdict(1.0))
        assertEquals(SpeedVerdict.BORDERLINE, speedVerdict(0.95))
        assertEquals(SpeedVerdict.FALLS_BEHIND, speedVerdict(0.94))
    }

    @Test
    fun speechIsScaledBackBySpeed() {
        assertEquals(22_845L, speechMs(15_230, 150))
        assertEquals(15_230L, speechMs(15_230, 100))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val stats = mapOf(
            A to ModelStats(listOf(sample(1, 2, 3), sample(4, 5, 6)), lastLoadMs = 4_800),
            B to ModelStats(listOf(sample()))
        )
        assertEquals(stats, decodeStats(encodeStats(stats)))
    }

    @Test
    fun decodeOfNothingOrJunkIsEmpty() {
        assertTrue(decodeStats(null).isEmpty())
        assertTrue(decodeStats("").isEmpty())
        assertTrue(decodeStats("{not json").isEmpty())
        assertTrue(decodeStats("[1, 2]").isEmpty())
    }

    @Test
    fun decodeIgnoresUnknownFieldsAndDefaultsTheLoad() {
        val json = """{"$A":{"samples":[{"speechMs":1,"decodeMs":2,"waitMs":3,"extra":true}],"new":1}}"""

        assertEquals(mapOf(A to ModelStats(listOf(sample(1, 2, 3)))), decodeStats(json))
    }

    @Test
    fun modelsWithStatsFollowTheCatalogue() {
        val stats = mapOf(
            "moonshine-base-en" to ModelStats(listOf(sample())),
            "unknown-model" to ModelStats(listOf(sample())),
            "whisper-small" to ModelStats(lastLoadMs = 100),
            "nemotron-streaming-en" to ModelStats(listOf(sample()))
        )

        assertEquals(
            listOf("nemotron-streaming-en", "moonshine-base-en"),
            modelsWithStats(stats).map { it.first.id }
        )
    }

    private companion object {
        const val A = "moonshine-base-en"
        const val B = "whisper-small"
    }
}
