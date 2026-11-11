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
     * [reasoningEffort] null leaves it to the model's default.
     * Cancel the calling coroutine to abort. Throws [CleanupException] on failures.
     */
    suspend fun cleanup(text: String, systemPrompt: String, model: String, reasoningEffort: String?): String

    /**
     * Opens a connection to the provider ahead of a [cleanup] call so that call skips the TCP/TLS setup.
     * Fire-and-forget: returns at once, never throws, and failures are ignored.
     */
    fun warmUp()

    /** Chat models usable for [cleanup], sorted by id. Throws [CleanupException] on failures. */
    suspend fun listModels(): List<CleanupModel>
}

/** A catalog model. [reasoningEfforts]: the effort values the model lists; null if unknown. */
data class CleanupModel(val id: String, val reasoningEfforts: List<String>?)
