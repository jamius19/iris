/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.stt.soniox

import dev.jamius.iris.cleanup.vercel.modelsNetworkErrorMessage
import dev.jamius.iris.stt.SttException
import dev.jamius.iris.stt.await
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

// Soniox's model catalogue: the settings' model menu, the mode (stream or upload) and the strict-hints check.
// https://docs.soniox.com/api-reference/stt/get_models

/**
 * A catalogue entry. [transcriptionMode]: `real_time` (WebSocket) or `async` (REST job).
 * [supportsLanguageHintsStrict]: `language_hints_strict` may be sent (else the server answers 400).
 */
data class SonioxModel(val id: String, val transcriptionMode: String?, val supportsLanguageHintsStrict: Boolean)

/** `GET` (with a key) lists the models; relative to a region's REST URL. */
internal const val SONIOX_MODELS_PATH = "v1/models"

/**
 * The models [apiKey] can use (`GET <baseUrl>/v1/models`), sorted by id. Without a key, or with one that can't be
 * sent as a header, nothing is requested. Throws [SttException] with a short message for the settings screen.
 */
suspend fun listSonioxModels(httpClient: OkHttpClient, apiKey: String?, baseUrl: String): List<SonioxModel> {
    val key = apiKey?.takeIf { it.isNotBlank() } ?: throw SttException("Set your Soniox API key to load models")
    // OkHttp rejects header values with control or non-ASCII chars; its message is not shown (it may quote the key).
    val request = try {
        Request.Builder().url("${baseUrl.trimEnd('/')}/$SONIOX_MODELS_PATH").header("Authorization", "Bearer $key")
            .build()
    } catch (e: IllegalArgumentException) {
        throw SttException("Invalid Soniox API key")
    }
    val (code, body) = try {
        httpClient.newCall(request).await()
    } catch (e: IOException) {
        throw SttException(modelsNetworkErrorMessage(e))
    }
    if (code !in 200..299) throw SttException(sonioxHttpErrorMessage(code, body))
    return parseSonioxModels(body) ?: throw SttException("Unexpected response from Soniox")
}

private val json = Json { ignoreUnknownKeys = true }

/**
 * `{models: [{id, transcription_mode, supports_language_hints_strict, ...}]}`, sorted by id; entries without a string
 * id are skipped, a missing or non-boolean flag is false. Null if the body has no `models` array.
 */
internal fun parseSonioxModels(body: String): List<SonioxModel>? {
    val models = try {
        (json.parseToJsonElement(body) as? JsonObject)?.get("models") as? JsonArray
    } catch (e: SerializationException) {
        null
    } ?: return null
    return models.mapNotNull { element ->
        val model = element as? JsonObject ?: return@mapNotNull null
        val id = model.string("id") ?: return@mapNotNull null
        SonioxModel(
            id = id,
            transcriptionMode = model.string("transcription_mode"),
            supportsLanguageHintsStrict = (model["supports_language_hints_strict"] as? JsonPrimitive)
                ?.takeIf { !it.isString }?.booleanOrNull == true
        )
    }.sortedBy { it.id }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * [model] streams over the WebSocket: its catalogue entry's `transcription_mode` is `real_time`; without an entry
 * (catalogue not loaded, or a typed id it doesn't list), its id starts with `stt-rt-`.
 */
fun sonioxStreams(model: String, catalog: List<SonioxModel>?): Boolean =
    catalog?.firstOrNull { it.id == model }?.transcriptionMode?.let { it == "real_time" } ?: model.startsWith("stt-rt-")
