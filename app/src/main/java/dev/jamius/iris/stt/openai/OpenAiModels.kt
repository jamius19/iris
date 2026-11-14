/*
* Author: Jamius Siam
* Since: 12/11/2026
*/

package dev.jamius.iris.stt.openai

import dev.jamius.iris.cleanup.vercel.modelsNetworkErrorMessage
import dev.jamius.iris.stt.SttException
import dev.jamius.iris.stt.await
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

// OpenAI's model list for the settings' model menu.
// https://developers.openai.com/api/reference/resources/models/methods/list

/** `GET` lists the models the key can use. */
internal const val MODELS_URL = "https://api.openai.com/v1/models"

/**
 * The transcription models [apiKey] can use (`GET /v1/models`), sorted. Without a key, or with one that can't be sent
 * as a header, nothing is requested. Throws [SttException] with a short message for the settings screen.
 */
suspend fun listOpenAiTranscriptionModels(
    httpClient: OkHttpClient,
    apiKey: String?,
    url: String = MODELS_URL,
): List<String> {
    val key = apiKey?.takeIf { it.isNotBlank() } ?: throw SttException("Set your OpenAI API key to load models")
    // OkHttp rejects header values with control or non-ASCII chars; its message is not shown (it may quote the key).
    val request = try {
        Request.Builder().url(url).header("Authorization", "Bearer $key").build()
    } catch (e: IllegalArgumentException) {
        throw SttException("Invalid OpenAI API key")
    }
    val (code, body) = try {
        httpClient.newCall(request).await()
    } catch (e: IOException) {
        throw SttException(modelsNetworkErrorMessage(e))
    }
    if (code !in 200..299) throw SttException(openAiHttpErrorMessage(code, body))
    return parseTranscriptionModelIds(body) ?: throw SttException("Unexpected response from OpenAI")
}

private val json = Json { ignoreUnknownKeys = true }

/**
 * The ids in `{ "data": [{ "id": ... }] }` that contain "transcribe" or "whisper" (any case), sorted; null if the body
 * has no `data` array.
 */
internal fun parseTranscriptionModelIds(body: String): List<String>? {
    val data = try {
        (json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonArray
    } catch (e: SerializationException) {
        null
    } ?: return null
    return data
        .mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { id -> id.isString }?.content }
        .filter { it.contains("transcribe", ignoreCase = true) || it.contains("whisper", ignoreCase = true) }
        .sorted()
}
