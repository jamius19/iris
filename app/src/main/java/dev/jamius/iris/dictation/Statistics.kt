/*
* Author: Jamius Siam
* Since: 14/11/2026
*/

package dev.jamius.iris.dictation

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.jamius.iris.data.settings.Settings
import dev.jamius.iris.data.settings.cleanupProviderLabel
import dev.jamius.iris.data.settings.sttProviderLabel
import dev.jamius.iris.stt.ondevice.MAX_SAMPLES
import dev.jamius.iris.stt.ondevice.ModelStats
import dev.jamius.iris.stt.ondevice.StatsSample
import dev.jamius.iris.stt.ondevice.decodeStats
import dev.jamius.iris.stt.ondevice.encodeStats
import dev.jamius.iris.stt.ondevice.speechMs
import dev.jamius.iris.stt.ondevice.withLoad
import dev.jamius.iris.stt.ondevice.withSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/** One cloud dictation: stop -> final text, the whole recording, and the audio the service bills. */
@Serializable
data class CloudSample(val waitMs: Long, val recordedMs: Long, val billedMs: Long)

/** A cloud provider + model's last [MAX_SAMPLES] dictations, oldest first; [title] as of the last one. */
@Serializable
data class CloudStats(val title: String, val samples: List<CloudSample> = emptyList())

/** A cleanup provider + model's last [MAX_SAMPLES] successful cleanup times, oldest first. */
@Serializable
data class CleanupStats(val title: String, val samplesMs: List<Long> = emptyList())

/**
 * Sums since statistics began (CP17) or the last reset: [recordedMs] of dictations with text, and [savedMs] = the time
 * typing their words would have taken minus the dictations' time ([typingSavedMs]; net, may be negative).
 */
@Serializable
data class Totals(val recordedMs: Long = 0, val savedMs: Long = 0)

/** Everything the Statistics page shows. */
data class StatsSnapshot(
    val onDevice: Map<String, ModelStats> = emptyMap(),
    val cloud: Map<String, CloudStats> = emptyMap(),
    val cleanup: Map<String, CleanupStats> = emptyMap(),
    val totals: Totals = Totals(),
)

/**
 * A dictation with text, as the controller measured it: [recordedMs] the whole recording, [billedMs] the audio the
 * service bills, [waitMs] stop -> final transcript, [doneMs] stop -> inserted (or copied), [words] the inserted
 * text's [wordCount] (no text, so none can leak into the stats layer).
 */
data class DictationRecord(
    val recordedMs: Long,
    val billedMs: Long,
    val waitMs: Long,
    val doneMs: Long,
    val words: Int,
)

/** Typing speed on a phone keyboard that dictating is compared with. */
const val TYPING_WPM = 38

/** Whitespace-separated words (CJK text without spaces counts as few words). */
fun wordCount(text: String): Int = text.split(Regex("\\s+")).count { it.isNotBlank() }

/** Typing [words] at [TYPING_WPM] minus the dictation's time (recording + stop -> done); negative if slower. */
fun typingSavedMs(words: Int, recordedMs: Long, doneMs: Long): Long =
    words * 60_000L / TYPING_WPM - (recordedMs + doneMs)

/** The cloud provider (an unknown id is OpenAI) and model; Deepgram's upload mode is its own entry. */
fun cloudKey(s: Settings): String {
    val provider = if (s.sttGateway || s.sttDeepgram || s.sttSoniox) s.sttProviderId else Settings.STT_PROVIDER_OPENAI
    val upload = if (s.sttDeepgram && s.deepgramUpload) "|upload" else ""
    return "$provider|${s.activeSttModel}$upload"
}

/** "OpenAI · gpt-live-transcribe", "Deepgram · nova-3 (upload)". */
fun cloudTitle(s: Settings): String {
    val upload = if (s.sttDeepgram && s.deepgramUpload) " (upload)" else ""
    return "${sttProviderLabel(s.sttProviderId)} · ${s.activeSttModel}$upload"
}

/** The cleanup provider (a custom one by its id) and model. */
fun cleanupKey(s: Settings): String = "${s.cleanupProviderId}|${s.activeCleanupModel}"

/** "Vercel AI Gateway · openai/gpt-6-luna", "<custom name> · <model>". */
fun cleanupTitle(s: Settings): String =
    "${cleanupProviderLabel(s.cleanupProviderId, s.customCleanupProviders)} · ${s.activeCleanupModel}"

/** Adds [d] to the totals and, for a cloud provider, to its window (last [MAX_SAMPLES]). */
fun StatsSnapshot.withDictation(s: Settings, d: DictationRecord): StatsSnapshot {
    val saved = typingSavedMs(d.words, d.recordedMs, d.doneMs)
    val totals = Totals(totals.recordedMs + d.recordedMs, totals.savedMs + saved)
    if (s.sttOnDevice) return copy(totals = totals)
    val key = cloudKey(s)
    val sample = CloudSample(d.waitMs, d.recordedMs, d.billedMs)
    val samples = (cloud[key]?.samples.orEmpty() + sample).takeLast(MAX_SAMPLES)
    return copy(cloud = cloud + (key to CloudStats(cloudTitle(s), samples)), totals = totals)
}

/** Adds a successful cleanup's [ms] to the selected provider + model's window (last [MAX_SAMPLES]). */
fun StatsSnapshot.withCleanup(s: Settings, ms: Long): StatsSnapshot {
    val key = cleanupKey(s)
    val samples = (cleanup[key]?.samplesMs.orEmpty() + ms).takeLast(MAX_SAMPLES)
    return copy(cleanup = cleanup + (key to CleanupStats(cleanupTitle(s), samples)))
}

/** The [p]th percentile by nearest rank (rank = ceil(p * n / 100)). Null without values. */
fun percentileMs(values: List<Long>, p: Int): Long? {
    if (values.isEmpty()) return null
    val rank = (p * values.size + 99) / 100
    return values.sorted()[rank - 1]
}

private val json = Json { ignoreUnknownKeys = true }

/** [empty] for null or malformed JSON. */
private inline fun <reified T> decodeOr(encoded: String?, empty: T): T {
    if (encoded == null) return empty
    return try {
        json.decodeFromString(encoded)
    } catch (e: IllegalArgumentException) {
        empty
    }
}

fun encodeCloud(cloud: Map<String, CloudStats>): String = json.encodeToString(cloud)

fun decodeCloud(encoded: String?): Map<String, CloudStats> = decodeOr(encoded, emptyMap())

fun encodeCleanup(cleanup: Map<String, CleanupStats>): String = json.encodeToString(cleanup)

fun decodeCleanup(encoded: String?): Map<String, CleanupStats> = decodeOr(encoded, emptyMap())

fun encodeTotals(totals: Totals): String = json.encodeToString(totals)

fun decodeTotals(encoded: String?): Totals = decodeOr(encoded, Totals())

/**
 * Dictation statistics for the Statistics page: on-device models' speed and load times, cloud providers' wait and
 * billed audio, cleanup times, all-time totals. Writes run on [scope] (callers never wait), serialized by DataStore.
 *
 * @param speedPercent the stored Speed, read when an on-device dictation is recorded.
 */
class Statistics(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val speedPercent: suspend () -> Int,
) {

    val stats: Flow<StatsSnapshot> = dataStore.data.map { it.snapshot() }

    /**
     * An on-device dictation with text: [audioMs] of model input (after Speed), [decodeMs] busy, [waitMs] stop ->
     * final.
     */
    fun recordOnDevice(modelId: String, audioMs: Long, decodeMs: Long, waitMs: Long) {
        scope.launch {
            val sample = StatsSample(speechMs(audioMs, speedPercent()), decodeMs.coerceAtLeast(1), waitMs)
            update { it.copy(onDevice = it.onDevice.withSample(modelId, sample)) }
        }
    }

    fun recordLoad(modelId: String, ms: Long) {
        scope.launch { update { it.copy(onDevice = it.onDevice.withLoad(modelId, ms)) } }
    }

    /** A dictation with text from any provider, with the session's settings [s]. */
    fun recordDictation(s: Settings, d: DictationRecord) {
        scope.launch { update { it.withDictation(s, d) } }
    }

    /** A cleanup that returned text after [ms], with the session's settings [s]. */
    fun recordCleanup(s: Settings, ms: Long) {
        scope.launch { update { it.withCleanup(s, ms) } }
    }

    /** Clears everything, totals included. */
    fun reset() {
        scope.launch {
            try {
                dataStore.edit { it.clear() }
            } catch (e: IOException) {
                Log.w(TAG, "Reset failed", e)
            }
        }
    }

    /** One `edit`: all keys are read and written together. */
    private suspend fun update(change: (StatsSnapshot) -> StatsSnapshot) {
        try {
            dataStore.edit {
                val next = change(it.snapshot())
                it[MODELS] = encodeStats(next.onDevice)
                it[CLOUD] = encodeCloud(next.cloud)
                it[CLEANUP] = encodeCleanup(next.cleanup)
                it[TOTALS] = encodeTotals(next.totals)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Write failed", e)
        }
    }

    private fun Preferences.snapshot() = StatsSnapshot(
        decodeStats(this[MODELS]),
        decodeCloud(this[CLOUD]),
        decodeCleanup(this[CLEANUP]),
        decodeTotals(this[TOTALS])
    )

    private companion object {
        const val TAG = "Statistics"
        val MODELS = stringPreferencesKey("models")
        val CLOUD = stringPreferencesKey("cloud")
        val CLEANUP = stringPreferencesKey("cleanup")
        val TOTALS = stringPreferencesKey("totals")
    }
}
