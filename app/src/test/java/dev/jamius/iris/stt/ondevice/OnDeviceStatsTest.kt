/*
* Author: Jamius Siam
* Since: 14/11/2026
*/

package dev.jamius.iris.stt.ondevice

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

    /** In memory: a file-backed store can't replace its file on a Windows host (see SecretStoreTest). */
    private class MemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private fun TestScope.stats(speedPercent: Int = 100) =
        OnDeviceStats(MemoryDataStore(), backgroundScope, speedPercent = { speedPercent })

    @Test
    fun recordDictationScalesSpeechBySpeedAndKeepsDecodeAtLeastOneMs() = runTest {
        val stats = stats(speedPercent = 150)
        stats.recordDictation(A, audioMs = 1_000, decodeMs = 0, waitMs = 300)
        runCurrent()

        assertEquals(mapOf(A to ModelStats(listOf(sample(1_500, 1, 300)))), stats.stats.first())
    }

    @Test
    fun recordLoadStoresTheLastLoadTime() = runTest {
        val stats = stats()
        stats.recordLoad(A, 4_800)
        stats.recordLoad(A, 3_100)
        runCurrent()

        assertEquals(mapOf(A to ModelStats(lastLoadMs = 3_100)), stats.stats.first())
    }

    @Test
    fun resetClearsSamplesAndLoadTimes() = runTest {
        val stats = stats()
        stats.recordDictation(A, audioMs = 1_000, decodeMs = 100, waitMs = 200)
        stats.recordLoad(B, 2_000)
        runCurrent()
        assertEquals(setOf(A, B), stats.stats.first().keys)

        stats.reset()
        runCurrent()

        assertTrue(stats.stats.first().isEmpty())
    }

    private companion object {
        const val A = "moonshine-base-en"
        const val B = "whisper-small"
    }
}
