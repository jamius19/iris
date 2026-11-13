/*
* Author: Jamius Siam
* Since: 14/11/2026
*/

package dev.jamius.iris.dictation

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.jamius.iris.data.settings.CustomCleanupProvider
import dev.jamius.iris.data.settings.Settings
import dev.jamius.iris.data.settings.toSettings
import dev.jamius.iris.stt.ondevice.ModelStats
import dev.jamius.iris.stt.ondevice.StatsSample
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

class StatisticsTest {

    private val openAi = emptyPreferences().toSettings(emptySet())
    private val gateway = openAi.copy(sttProviderId = Settings.STT_PROVIDER_VERCEL_GATEWAY)
    private val deepgram = openAi.copy(sttProviderId = Settings.STT_PROVIDER_DEEPGRAM)
    private val soniox = openAi.copy(sttProviderId = Settings.STT_PROVIDER_SONIOX)
    private val onDevice = openAi.copy(sttProviderId = Settings.STT_PROVIDER_ON_DEVICE)

    private val groq = CustomCleanupProvider(
        id = "u-1",
        name = "Groq",
        baseUrl = "https://api.groq.com/openai/v1",
        model = "llama"
    )
    private val custom = openAi.copy(customCleanupProviders = listOf(groq), cleanupProviderId = groq.id)

    private fun record(recordedMs: Long = 10_000, billedMs: Long = 6_000, waitMs: Long = 800, doneMs: Long = 1_200) =
        DictationRecord(recordedMs, billedMs, waitMs, doneMs, 38)

    @Test
    fun cloudKeysAndTitles() {
        assertEquals("openai|gpt-live-transcribe", cloudKey(openAi))
        assertEquals("OpenAI · gpt-live-transcribe", cloudTitle(openAi))
        // An unknown provider id is OpenAI.
        val unknown = openAi.copy(sttProviderId = "nope")
        assertEquals("openai|gpt-live-transcribe", cloudKey(unknown))
        assertEquals("OpenAI · gpt-live-transcribe", cloudTitle(unknown))
        assertEquals("vercel-gateway|openai/gpt-live-transcribe", cloudKey(gateway))
        assertEquals("Vercel AI Gateway · openai/gpt-live-transcribe", cloudTitle(gateway))
        assertEquals("soniox|stt-rt-v6", cloudKey(soniox))
        assertEquals("Soniox · stt-rt-v6", cloudTitle(soniox))
        assertEquals("deepgram|nova-3", cloudKey(deepgram))
        assertEquals("Deepgram · nova-3", cloudTitle(deepgram))
        val upload = deepgram.copy(deepgramUpload = true)
        assertEquals("deepgram|nova-3|upload", cloudKey(upload))
        assertEquals("Deepgram · nova-3 (upload)", cloudTitle(upload))
        // Deepgram's switch means nothing for another provider.
        assertEquals("openai|gpt-live-transcribe", cloudKey(openAi.copy(deepgramUpload = true)))
    }

    @Test
    fun cleanupKeysAndTitles() {
        assertEquals("vercel-gateway|openai/gpt-6-luna", cleanupKey(openAi))
        assertEquals("Vercel AI Gateway · openai/gpt-6-luna", cleanupTitle(openAi))
        val openRouter = openAi.copy(cleanupProviderId = Settings.CLEANUP_PROVIDER_OPENROUTER)
        assertEquals("openrouter|openai/gpt-6-luna", cleanupKey(openRouter))
        assertEquals("OpenRouter · openai/gpt-6-luna", cleanupTitle(openRouter))
        // A custom provider is keyed by its id and titled by its name.
        assertEquals("u-1|llama", cleanupKey(custom))
        assertEquals("Groq · llama", cleanupTitle(custom))
    }

    @Test
    fun wordCounts() {
        assertEquals(0, wordCount(""))
        assertEquals(0, wordCount("  \n "))
        assertEquals(1, wordCount("Hello"))
        assertEquals(4, wordCount("  Hello,   world.\nNew\tline "))
    }

    @Test
    fun typingSaved() {
        // 38 words take a minute to type; the dictation took 10 s + 1.2 s.
        assertEquals(48_800L, typingSavedMs(38, recordedMs = 10_000, doneMs = 1_200))
        // 3 words: 4,736 ms of typing, slower to dictate.
        assertEquals(-6_464L, typingSavedMs(3, recordedMs = 10_000, doneMs = 1_200))
        assertEquals(-500L, typingSavedMs(0, recordedMs = 0, doneMs = 500))
    }

    @Test
    fun onDeviceDictationOnlyCountsInTheTotals() {
        val after = StatsSnapshot().withDictation(onDevice, record())

        assertTrue(after.cloud.isEmpty())
        assertEquals(Totals(recordedMs = 10_000, savedMs = 48_800), after.totals)
    }

    @Test
    fun cloudDictationAddsASampleAndTheTotals() {
        val after = StatsSnapshot(totals = Totals(1_000, -2_000))
            .withDictation(deepgram, record())
            .withDictation(gateway, record(recordedMs = 5_000, billedMs = 5_000, waitMs = 300, doneMs = 300))

        assertEquals(
            CloudStats("Deepgram · nova-3", listOf(CloudSample(800, 10_000, 6_000))),
            after.cloud.getValue("deepgram|nova-3")
        )
        assertEquals(
            listOf(CloudSample(300, 5_000, 5_000)),
            after.cloud.getValue("vercel-gateway|openai/gpt-live-transcribe").samples
        )
        // 1,000 + 10,000 + 5,000 recorded; -2,000 + 48,800 + (60,000 - 5,300) saved.
        assertEquals(Totals(16_000, 101_500), after.totals)
    }

    @Test
    fun theTwentyFirstCloudSampleDropsTheOldest() {
        var stats = StatsSnapshot()
        for (i in 1..21) stats = stats.withDictation(openAi, record(waitMs = i.toLong()))

        assertEquals((2L..21L).toList(), stats.cloud.getValue(cloudKey(openAi)).samples.map { it.waitMs })
        assertEquals(210_000L, stats.totals.recordedMs)
    }

    @Test
    fun cleanupKeepsTheLastTwentyAndRefreshesTheTitle() {
        var stats = StatsSnapshot()
        for (i in 1..21) stats = stats.withCleanup(custom, i.toLong())
        val renamed = custom.copy(customCleanupProviders = listOf(groq.copy(name = "Groq Cloud")))
        stats = stats.withCleanup(renamed, 22)

        assertEquals(CleanupStats("Groq Cloud · llama", (3L..22L).toList()), stats.cleanup.getValue("u-1|llama"))
        assertEquals(setOf("u-1|llama"), stats.cleanup.keys)
    }

    @Test
    fun percentiles() {
        assertNull(percentileMs(emptyList(), 75))
        assertEquals(7L, percentileMs(listOf(7), 75))
        assertEquals(7L, percentileMs(listOf(7), 90))
        assertEquals(2L, percentileMs(listOf(1, 2), 75))
        assertEquals(2L, percentileMs(listOf(1, 2), 90))
        assertEquals(4L, percentileMs((1L..5L).toList(), 75))
        assertEquals(5L, percentileMs((1L..5L).toList(), 90))
        assertEquals(15L, percentileMs((1L..20L).toList(), 75))
        assertEquals(18L, percentileMs((1L..20L).toList(), 90))
        assertEquals(4L, percentileMs(listOf(5, 1, 4, 2, 3), 75))
    }

    @Test
    fun encodeDecodeRoundTrips() {
        val cloud = mapOf("openai|x" to CloudStats("OpenAI · x", listOf(CloudSample(1, 2, 3), CloudSample(4, 5, 6))))
        val cleanup = mapOf("openrouter|y" to CleanupStats("OpenRouter · y", listOf(1, 2, 3)))
        val totals = Totals(123, -45)

        assertEquals(cloud, decodeCloud(encodeCloud(cloud)))
        assertEquals(cleanup, decodeCleanup(encodeCleanup(cleanup)))
        assertEquals(totals, decodeTotals(encodeTotals(totals)))
    }

    @Test
    fun decodeOfNothingOrJunkIsEmpty() {
        for (junk in listOf(null, "", "{not json", "[1, 2]", "null")) {
            assertTrue(decodeCloud(junk).isEmpty())
            assertTrue(decodeCleanup(junk).isEmpty())
            assertEquals(Totals(), decodeTotals(junk))
        }
    }

    /** In memory: a file-backed store can't replace its file on a Windows host (see SecretStoreTest). */
    private class MemoryDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private fun TestScope.statistics(speedPercent: Int = 100, initial: Preferences = emptyPreferences()) =
        Statistics(MemoryDataStore(initial), backgroundScope, speedPercent = { speedPercent })

    @Test
    fun cp16ModelsJsonStillDecodes() = runTest {
        val json = """{"$A":{"samples":[{"speechMs":1500,"decodeMs":1,"waitMs":300}],"lastLoadMs":4800}}"""
        val stats = statistics(initial = preferencesOf(stringPreferencesKey("models") to json))

        assertEquals(
            StatsSnapshot(onDevice = mapOf(A to ModelStats(listOf(StatsSample(1_500, 1, 300)), lastLoadMs = 4_800))),
            stats.stats.first()
        )
    }

    @Test
    fun recordOnDeviceScalesSpeechBySpeedAndKeepsDecodeAtLeastOneMs() = runTest {
        val stats = statistics(speedPercent = 150)
        stats.recordOnDevice(A, audioMs = 1_000, decodeMs = 0, waitMs = 300)
        runCurrent()

        // The provider's own sample: no totals (the controller records those).
        assertEquals(
            StatsSnapshot(onDevice = mapOf(A to ModelStats(listOf(StatsSample(1_500, 1, 300))))),
            stats.stats.first()
        )
    }

    @Test
    fun recordLoadStoresTheLastLoadTime() = runTest {
        val stats = statistics()
        stats.recordLoad(A, 4_800)
        stats.recordLoad(A, 3_100)
        runCurrent()

        assertEquals(mapOf(A to ModelStats(lastLoadMs = 3_100)), stats.stats.first().onDevice)
    }

    @Test
    fun recordDictationCountsEachDictationOnceInTheTotals() = runTest {
        val stats = statistics()
        stats.recordOnDevice(A, audioMs = 1_000, decodeMs = 100, waitMs = 200)
        stats.recordDictation(onDevice, record(recordedMs = 2_000, doneMs = 0))
        stats.recordDictation(soniox, record(recordedMs = 3_000, billedMs = 3_000, doneMs = 0))
        runCurrent()

        val snapshot = stats.stats.first()
        assertEquals(Totals(5_000, 60_000 - 2_000 + 60_000 - 3_000), snapshot.totals)
        assertEquals(setOf("soniox|stt-rt-v6"), snapshot.cloud.keys)
        assertEquals(1, snapshot.onDevice.getValue(A).samples.size)
    }

    @Test
    fun recordCleanupAddsToTheSelectedProvider() = runTest {
        val stats = statistics()
        stats.recordCleanup(openAi, 900)
        stats.recordCleanup(openAi, 1_100)
        runCurrent()

        assertEquals(
            mapOf(cleanupKey(openAi) to CleanupStats("Vercel AI Gateway · openai/gpt-6-luna", listOf(900, 1_100))),
            stats.stats.first().cleanup
        )
    }

    @Test
    fun resetClearsEverything() = runTest {
        val stats = statistics()
        stats.recordOnDevice(A, audioMs = 1_000, decodeMs = 100, waitMs = 200)
        stats.recordLoad(B, 2_000)
        stats.recordDictation(openAi, record())
        stats.recordCleanup(openAi, 900)
        runCurrent()
        val before = stats.stats.first()
        assertEquals(setOf(A, B), before.onDevice.keys)
        assertEquals(1, before.cloud.size)
        assertEquals(1, before.cleanup.size)

        stats.reset()
        runCurrent()

        assertEquals(StatsSnapshot(), stats.stats.first())
    }

    private companion object {
        const val A = "moonshine-base-en"
        const val B = "whisper-small"
    }
}
