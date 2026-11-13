/*
* Author: Jamius Siam
* Since: 14/11/2026
*/

package dev.jamius.iris.stt.ondevice

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/** One dictation: recorded speech (before Speed), the recognizer's busy time, stop -> final text. */
@Serializable
data class StatsSample(val speechMs: Long, val decodeMs: Long, val waitMs: Long)

/** A model's last [MAX_SAMPLES] dictations, oldest first, and its last load time. */
@Serializable
data class ModelStats(val samples: List<StatsSample> = emptyList(), val lastLoadMs: Long? = null)

enum class SpeedVerdict { KEEPS_UP, BORDERLINE, FALLS_BEHIND }

const val MAX_SAMPLES = 20

/** Adds [sample] to [modelId]'s samples, keeping the last [MAX_SAMPLES]. */
fun Map<String, ModelStats>.withSample(modelId: String, sample: StatsSample): Map<String, ModelStats> {
    val current = this[modelId] ?: ModelStats()
    return this + (modelId to current.copy(samples = (current.samples + sample).takeLast(MAX_SAMPLES)))
}

fun Map<String, ModelStats>.withLoad(modelId: String, ms: Long): Map<String, ModelStats> =
    this + (modelId to (this[modelId] ?: ModelStats()).copy(lastLoadMs = ms))

/** Speech per decode time over all samples (a ratio of sums: long dictations weigh more); null without samples. */
fun ModelStats.speedFactor(): Double? {
    if (samples.isEmpty()) return null
    return samples.sumOf { it.speechMs }.toDouble() / samples.sumOf { it.decodeMs }
}

/** The median stop -> final text; an even count takes the mean of the middle two. Null without samples. */
fun ModelStats.medianWaitMs(): Long? {
    if (samples.isEmpty()) return null
    val sorted = samples.map { it.waitMs }.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}

/** [x] rounded to one decimal, as the page shows it: the verdict and the speed text both use it, so they agree. */
fun roundToTenth(x: Double): Double = Math.round(x * 10) / 10.0

/** On the factor rounded like the text ([roundToTenth]): 1.45 keeps up, 0.95 is borderline. */
fun speedVerdict(factor: Double): SpeedVerdict = when {
    roundToTenth(factor) >= 1.5 -> SpeedVerdict.KEEPS_UP
    roundToTenth(factor) >= 1.0 -> SpeedVerdict.BORDERLINE
    else -> SpeedVerdict.FALLS_BEHIND
}

/** Recorded speech from the model input's [audioMs], which Speed shortened by [speedPercent]. */
fun speechMs(audioMs: Long, speedPercent: Int): Long = audioMs * speedPercent / 100

private val json = Json { ignoreUnknownKeys = true }

fun encodeStats(stats: Map<String, ModelStats>): String = json.encodeToString(stats)

/** None for null or malformed JSON. */
fun decodeStats(encoded: String?): Map<String, ModelStats> {
    if (encoded == null) return emptyMap()
    return try {
        json.decodeFromString(encoded)
    } catch (e: IllegalArgumentException) {
        emptyMap()
    }
}

/** The catalogue's models with at least one sample, in menu order (unknown ids dropped). */
fun modelsWithStats(stats: Map<String, ModelStats>): List<Pair<OnDeviceModel, ModelStats>> =
    ON_DEVICE_MODELS.mapNotNull { model -> stats[model.id]?.takeIf { it.samples.isNotEmpty() }?.let { model to it } }

/**
 * Per-model statistics on this phone, for the Statistics page. Writes run on [scope] (callers never wait), serialized
 * by DataStore.
 *
 * @param speedPercent the stored Speed, read when a dictation is recorded.
 */
class OnDeviceStats(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val speedPercent: suspend () -> Int,
) {

    val stats: Flow<Map<String, ModelStats>> = dataStore.data.map { decodeStats(it[KEY]) }

    /** A dictation with text: [audioMs] of model input (after Speed), [decodeMs] busy, [waitMs] stop -> final. */
    fun recordDictation(modelId: String, audioMs: Long, decodeMs: Long, waitMs: Long) {
        scope.launch {
            val sample = StatsSample(speechMs(audioMs, speedPercent()), decodeMs.coerceAtLeast(1), waitMs)
            update { it.withSample(modelId, sample) }
        }
    }

    fun recordLoad(modelId: String, ms: Long) {
        scope.launch { update { it.withLoad(modelId, ms) } }
    }

    /** Clears every model's samples and load time. */
    fun reset() {
        scope.launch {
            try {
                dataStore.edit { it.remove(KEY) }
            } catch (e: IOException) {
                Log.w(TAG, "Reset failed", e)
            }
        }
    }

    private suspend fun update(change: (Map<String, ModelStats>) -> Map<String, ModelStats>) {
        try {
            dataStore.edit { it[KEY] = encodeStats(change(decodeStats(it[KEY]))) }
        } catch (e: IOException) {
            Log.w(TAG, "Write failed", e)
        }
    }

    private companion object {
        const val TAG = "OnDeviceStats"
        val KEY = stringPreferencesKey("models")
    }
}
