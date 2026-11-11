/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.cleanup

/** A user-readable cleanup failure (missing key, auth, network, server error or unusable response). */
class CleanupException(message: String) : Exception(message)

/** LLM cleanup of a dictated transcript. A new provider is just a new implementation. */
interface CleanupProvider {

    /**
     * Cleans up [text] following [systemPrompt] with [model] and returns the cleaned text (never blank).
     * [onPartial] gets the cleaned text so far whenever it grows (display only; may differ from the result).
     * Cancel the calling coroutine to abort. Throws [CleanupException] on failures.
     * [onPartial] runs in the caller's context.
     */
    suspend fun cleanup(text: String, systemPrompt: String, model: String, onPartial: (String) -> Unit): String

    /**
     * Opens a connection to the provider ahead of a [cleanup] call so that call skips the TCP/TLS setup.
     * Fire-and-forget: returns at once, never throws, and failures are ignored.
     */
    fun warmUp()

    /** Ids of the chat models usable for [cleanup], sorted. Throws [CleanupException] on failures. */
    suspend fun listModels(): List<String>
}
