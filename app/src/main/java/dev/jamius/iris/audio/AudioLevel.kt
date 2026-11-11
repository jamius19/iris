/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.audio

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

private const val MIN_DB = -55f
private const val MAX_DB = -12f

/**
 * Loudness of the little-endian PCM16 samples in `bytes[0 until length]` (a trailing odd byte is ignored), 0..1:
 * RMS in dBFS mapped from [MIN_DB]..[MAX_DB], then eased (`pow 0.7`) so quiet speech still shows.
 */
internal fun pcm16Level(bytes: ByteArray, length: Int = bytes.size): Float {
    val samples = length / 2
    if (samples == 0) return 0f
    var sum = 0.0
    for (i in 0 until samples) {
        val sample = (bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)
        sum += sample.toDouble() * sample
    }
    val rms = sqrt(sum / samples)
    if (rms == 0.0) return 0f
    val db = 20 * log10(rms / 32768).toFloat()
    return ((db - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 1f).pow(0.7f)
}
