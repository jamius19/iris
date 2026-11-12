/*
* Author: Jamius Siam
* Since: 12/11/2026
*/

package dev.jamius.iris.audio

import dev.jamius.iris.audio.sonic.Sonic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * [this] PCM16 mono audio at [sampleRate], [speed] times faster with Sonic (pitch kept). Sonic holds back up to ~30 ms;
 * that tail is flushed when [this] completes normally. Errors propagate unchanged (no flush). Never emits empty arrays.
 */
internal fun Flow<ByteArray>.timeStretched(sampleRate: Int, speed: Float): Flow<ByteArray> = flow {
    val sonic = Sonic(sampleRate, 1).apply { setSpeed(speed) }
    collect {
        sonic.writeBytesToStream(it, it.size)
        sonic.output()?.let { out -> emit(out) }
    }
    sonic.flushStream()
    sonic.output()?.let { emit(it) }
}

/** The samples Sonic has ready, as PCM16 LE bytes, or null if none. */
private fun Sonic.output(): ByteArray? =
    samplesAvailable().takeIf { it > 0 }?.let { n -> ByteArray(n * 2).also { readBytesFromStream(it, it.size) } }
