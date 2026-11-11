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

    /**
     * Streams [audio] (PCM16 mono 24 kHz chunks) to the service and reports the whole live transcript through
     * [onPartial] whenever it changes. Once [audio] completes, finalizes and returns the final transcript.
     * Cancel the calling coroutine to abort and discard the session.
     *
     * Throws [SttException] on provider failures; exceptions from [audio] propagate unchanged.
     * [onPartial] runs in the caller's context.
     *
     * @param language ISO-639-1 code, or blank to auto-detect.
     */
    suspend fun transcribe(
        audio: Flow<ByteArray>,
        model: String,
        language: String,
        onPartial: (String) -> Unit,
    ): String

    /**
     * Opens a connection to the provider ahead of a [transcribe] call so it skips the DNS/TCP/TLS setup.
     * Fire-and-forget: returns at once, never throws, and failures are ignored. Cheap to call often: repeat calls
     * are skipped while an earlier warm-up's connection should still be usable.
     */
    fun warmUp()
}
