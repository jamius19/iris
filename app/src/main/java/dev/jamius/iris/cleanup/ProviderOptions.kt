/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.cleanup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val JSON_NUMBER = Regex("""-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""")

/**
 * Provider options as typed in Settings: null when [text] is blank, else the JSON object it holds. Throws
 * [IllegalArgumentException] with a short user-readable message when it isn't valid JSON or isn't an object.
 */
internal fun parseProviderOptions(text: String): JsonObject? {
    if (text.isBlank()) return null
    // The default Json (no comments, trailing commas or unquoted keys) plus a check that every value is a quoted
    // string, number, true/false or null.
    val element = try {
        Json.parseToJsonElement(text)
    } catch (e: SerializationException) {
        throw IllegalArgumentException("Not valid JSON")
    }
    require(element is JsonObject) { "Must be a JSON object: { ... }" }
    if (!element.isStrictJson()) throw IllegalArgumentException("Not valid JSON")
    return element
}

/** The default Json still takes unquoted values (`[openai]`, `True`, `NaN`) as literals and writes them back as is. */
private fun JsonElement.isStrictJson(): Boolean = when (this) {
    is JsonObject -> values.all { it.isStrictJson() }
    is JsonArray -> all { it.isStrictJson() }
    JsonNull -> true
    is JsonPrimitive -> isString || content == "true" || content == "false" || JSON_NUMBER.matches(content)
}
