/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.cleanup

import kotlinx.serialization.json.JsonObject

/** A user-readable cleanup failure (missing key, auth, network, server error or unusable response). */
class CleanupException(message: String) : Exception(message)

/** LLM cleanup of a dictated transcript. A new provider is just a new implementation. */
interface CleanupProvider {

    /**
     * Cleans up [text] following [systemPrompt] with [model] and returns the cleaned text (never blank).
     * [reasoningEffort] null leaves it to the model's default. [providerOptions]: the user's provider options, sent
     * under the provider's own key; null = none.
     * Cancel the calling coroutine to abort. Throws [CleanupException] on failures.
     */
    suspend fun cleanup(
        text: String,
        systemPrompt: String,
        model: String,
        reasoningEffort: String?,
        providerOptions: JsonObject?,
    ): String

    /**
     * Opens a connection to the provider ahead of a [cleanup] call so that call skips the TCP/TLS setup.
     * Fire-and-forget: returns at once, never throws, and failures are ignored.
     */
    fun warmUp()

    /**
     * The provider's model catalog, sorted by id (Gateway: all model kinds, also used for speech-to-text; callers
     * filter. OpenRouter: chat models with structured output). Throws [CleanupException] on failures.
     */
    suspend fun listModels(): List<GatewayModel>
}

/**
 * A catalog model. [type]: its kind (e.g. `language`, `transcription`), null if not given; [tags]: e.g.
 * `websocket-transcription`; [reasoningEfforts]: the effort values the model lists, null if unknown.
 */
data class GatewayModel(
    val id: String,
    val type: String?,
    val tags: List<String>,
    val reasoningEfforts: List<String>?,
)
