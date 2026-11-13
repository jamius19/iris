/*
* Author: Jamius Siam
* Since: 14/11/2026
*/

package dev.jamius.iris.stt.ondevice

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

/** An even count takes the mean of the middle two. Null without values. */
fun median(values: List<Long>): Long? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}

/** The median stop -> final text; an even count takes the mean of the middle two. Null without samples. */
fun ModelStats.medianWaitMs(): Long? = median(samples.map { it.waitMs })

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
