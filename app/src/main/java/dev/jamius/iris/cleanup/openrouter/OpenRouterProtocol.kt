/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.cleanup.openrouter

import dev.jamius.iris.cleanup.GatewayModel
import dev.jamius.iris.data.settings.Settings
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// OpenRouter's OpenAI-compatible Chat Completions API, free of I/O for unit tests. The request body and completion
// parsing are the Gateway's (`cleanup.vercel.GatewayProtocol.kt`).
// https://openrouter.ai/docs/api_reference/overview, /guides/features/structured-outputs,
// /guides/best-practices/reasoning-tokens, /api_reference/errors-and-debugging, /guides/routing/provider-selection

internal const val OPENROUTER_CHAT_COMPLETIONS_URL = "https://openrouter.ai/api/v1/chat/completions"

/** Public catalog: listed and warmed up without a key. */
internal const val OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1/models"

/** The list request's query: only models that take a json_schema `response_format`. */
internal const val STRUCTURED_OUTPUTS_QUERY = "supported_parameters=structured_outputs"

/** OpenRouter's top-level key for the user's provider (routing) options. */
internal const val OPENROUTER_OPTIONS_KEY = "provider"

/**
 * Models with a non-blank id from a `GET /api/v1/models` body (`{data:[{id, reasoning?:{supported_efforts:[..]}}]}`),
 * sorted by id, without the `:batch` variants (batch API only). Efforts are put in [Settings.REASONING_EFFORTS] order
 * (OpenRouter lists them high to low; unknown values last); null if missing or empty. Null if not that shape.
 */
internal fun parseOpenRouterModels(body: String): List<GatewayModel>? {
    val data = parseObject(body)?.get("data") as? JsonArray ?: return null
    return data.mapNotNull { entry ->
        val model = entry as? JsonObject ?: return@mapNotNull null
        model.string("id")
            ?.takeIf { it.isNotBlank() && !it.endsWith(BATCH_SUFFIX) }
            ?.let { GatewayModel(it, null, emptyList(), model.reasoningEfforts()) }
    }.sortedBy { it.id }
}

/** `reasoning.supported_efforts`, low to high; null if missing, not that shape or empty. */
private fun JsonObject.reasoningEfforts(): List<String>? =
    ((this["reasoning"] as? JsonObject)?.get("supported_efforts") as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        ?.takeIf { it.isNotEmpty() }
        ?.sortedBy { effort -> Settings.REASONING_EFFORTS.indexOf(effort).takeIf { it >= 0 } ?: Int.MAX_VALUE }

/**
 * The error a 200 response can carry instead of a completion: a top-level `error` (no `choices`), or
 * `choices[0].error` / `choices[0].finish_reason == "error"`. Its `message` (else "generation failed"); null if none.
 */
internal fun parseOpenRouterError(body: String): String? {
    val root = parseObject(body) ?: return null
    val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
    val error = if (choice == null) {
        root.present("error") ?: return null
    } else {
        choice.present("error") ?: if (choice.string("finish_reason") == "error") JsonNull else return null
    }
    return (error as? JsonObject)?.string("message")?.takeIf { it.isNotBlank() } ?: "generation failed"
}

/** For a non-2xx response; [body] may carry `{ "error": { "code", "message" } }`. */
internal fun openRouterHttpErrorMessage(code: Int, body: String): String = when {
    code == 401 -> "OpenRouter rejected the API key. Check it in Iris settings."
    code == 402 -> "OpenRouter account is out of credits"
    code == 429 -> "OpenRouter rate limit reached"
    code == 502 -> "OpenRouter: the model's provider failed (HTTP 502)"
    code == 503 -> "No OpenRouter provider available for this model (check provider options)"
    code >= 500 -> "OpenRouter server error (HTTP $code)"
    else -> parseObject(body)?.let { it["error"] as? JsonObject }?.string("message")
        ?.let { "OpenRouter error: $it" }
        ?: "OpenRouter request failed (HTTP $code)"
}

private const val BATCH_SUFFIX = ":batch"

private val json = Json { ignoreUnknownKeys = true }

private fun parseObject(text: String): JsonObject? = try {
    json.parseToJsonElement(text) as? JsonObject
} catch (e: SerializationException) {
    null
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** [key]'s value unless it is missing or JSON `null`. */
private fun JsonObject.present(key: String) = this[key]?.takeIf { it !is JsonNull }
