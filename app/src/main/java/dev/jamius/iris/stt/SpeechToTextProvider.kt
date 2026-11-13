/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.stt

import kotlinx.coroutines.flow.Flow

/** A user-readable speech-to-text failure (missing key, auth, network or server error). */
class SttException(message: String) : Exception(message)

/** Streaming speech-to-text. A new provider is just a new implementation. */
interface SpeechToTextProvider {

    /** The rate (Hz) [transcribe] takes [model]'s audio at. */
    fun sampleRate(model: String): Int = 24_000

    /**
     * Sends audio while recording (holds a socket: gets VAD keep-alive audio); false = no socket while recording, so
     * no keep-alive: upload providers (no `onPartial`) and the on-device one.
     */
    val streams: Boolean get() = true

    /** [model] reports text while recording. Picks the VAD hangover. */
    fun liveText(model: String): Boolean = streams

    /**
     * The audio may be sped up (Settings' Speed); false = always sent as recorded (Soniox real-time bills wall-clock
     * time and wants real-time pace, so a speed-up saves nothing and adds risk).
     */
    val allowsSpeedUp: Boolean get() = true

    /**
     * [transcribe]'s audio carries an empty array at the end of each speech segment the VAD gate detects (Skip Silence
     * on); for providers that decode per segment.
     */
    val wantsSegmentEnds: Boolean get() = false

    /**
     * Streams [audio] (PCM16 mono chunks at [sampleRate] of [model]) to the service and reports the whole live
     * transcript through [onPartial] whenever it changes. Once [audio] completes, finalizes and returns the final
     * transcript. Cancel the calling coroutine to abort and discard the session. [audio] holds empty arrays (segment
     * ends) only with [wantsSegmentEnds].
     *
     * Throws [SttException] on provider failures; exceptions from [audio] propagate unchanged.
     * [onPartial] runs in the caller's context.
     *
     * @param language ISO-639-1 code, or blank to auto-detect (Soniox: a comma-separated list).
     * @param timeoutMs how long to wait for the final transcript once all audio is sent.
     */
    suspend fun transcribe(
        audio: Flow<ByteArray>,
        model: String,
        language: String,
        timeoutMs: Long,
        onPartial: (String) -> Unit,
    ): String

    /**
     * Opens a connection to the provider ahead of a [transcribe] call so it skips the DNS/TCP/TLS setup.
     * Fire-and-forget: returns at once, never throws, and failures are ignored. Cheap to call often: repeat calls
     * are skipped while an earlier warm-up's connection should still be usable.
     */
    fun warmUp()
}
