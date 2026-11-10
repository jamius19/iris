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
     * Cancel the calling coroutine to abort. Throws [CleanupException] on failures.
     */
    suspend fun cleanup(text: String, systemPrompt: String, model: String): String
}
