/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.stt.soniox

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// Request building and message parsing for Soniox's speech-to-text APIs, free of I/O for unit tests. Checked
// 2026-11-13:
// https://docs.soniox.com/api-reference/stt/websocket-api
// https://docs.soniox.com/authentication/websocket-authentication
// https://docs.soniox.com/stt/rt/connection-keepalive, https://docs.soniox.com/stt/rt/error-handling
// https://docs.soniox.com/api-reference/stt/files/upload_file
// https://docs.soniox.com/api-reference/stt/files/delete_file
// https://docs.soniox.com/api-reference/stt/transcriptions/create_transcription (also get_transcription,
// get_transcription_transcript, delete_transcription)
// https://docs.soniox.com/api-reference/stt/get_models, https://docs.soniox.com/data-residency
// https://docs.soniox.com/api-reference/errors

/**
 * A Soniox project's data-residency region: its key works only on that region's domains. The WebSocket URL is
 * `https` (OkHttp upgrades it: `wss` on the wire).
 */
enum class SonioxRegion(val id: String, val label: String, val restUrl: String, val webSocketUrl: String) {
    US("us", "United States", "https://api.soniox.com", "https://stt-rt.soniox.com/transcribe-websocket"),
    EU("eu", "European Union", "https://api.eu.soniox.com", "https://stt-rt.eu.soniox.com/transcribe-websocket"),
    JP("jp", "Japan", "https://api.jp.soniox.com", "https://stt-rt.jp.soniox.com/transcribe-websocket"),
    IN("in", "India", "https://api.in.soniox.com", "https://stt-rt.in.soniox.com/transcribe-websocket"),
}

/** The region with [id]; an unknown id means [SonioxRegion.US]. */
fun sonioxRegion(id: String): SonioxRegion = SonioxRegion.entries.firstOrNull { it.id == id } ?: SonioxRegion.US

/** PCM16 mono rate of both modes: the only rate the docs show, and the VAD's own rate. */
internal const val SONIOX_SAMPLE_RATE = 16_000

/** An empty TEXT frame ends the stream (an empty binary frame is just an empty audio chunk: never sent). */
internal const val END_OF_AUDIO = ""

/** The codes in [language] (comma-separated): trimmed, lowercased, unique; blank = none (auto-detect). */
internal fun sonioxLanguageHints(language: String): List<String> =
    language.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

/**
 * The WebSocket's first (text) frame: raw PCM16 mono at 16 kHz. [strict] (`language_hints_strict`) only with hints,
 * and only for a model whose catalogue entry supports it (else the server answers 400). The key goes in the
 * handshake's header, never in `api_key`.
 */
internal fun sonioxStartMessage(model: String, hints: List<String>, strict: Boolean): String = buildJsonObject {
    put("model", model)
    put("audio_format", "pcm_s16le")
    put("sample_rate", SONIOX_SAMPLE_RATE)
    put("num_channels", 1)
    putHints(hints, strict)
}.toString()

/** `POST /v1/transcriptions` body for the uploaded file [fileId]; hints as in [sonioxStartMessage]. */
internal fun sonioxCreateTranscriptionBody(
    model: String,
    fileId: String,
    hints: List<String>,
    strict: Boolean,
): String = buildJsonObject {
    put("model", model)
    put("file_id", fileId)
    putHints(hints, strict)
}.toString()

private fun JsonObjectBuilder.putHints(hints: List<String>, strict: Boolean) {
    if (hints.isEmpty()) return
    putJsonArray("language_hints") { hints.forEach { add(JsonPrimitive(it)) } }
    if (strict) put("language_hints_strict", true)
}

/** A transcript piece; tokens carry their own spacing. [isFinal]: never repeated or changed. */
internal data class SonioxToken(val text: String, val isFinal: Boolean)

/** The server messages (and socket outcomes) the streaming client acts on. */
internal sealed interface SonioxEvent {
    /** This response's tokens (finals once, then the whole non-final tail); [finished]: the transcript is complete. */
    data class Tokens(val tokens: List<SonioxToken>, val finished: Boolean) : SonioxEvent

    /** An error frame (the server closes the socket right after it). */
    data class Error(val code: Int, val message: String?) : SonioxEvent

    /** Ends the session; [message] is user-readable. Not parsed: from the socket's failure or close. */
    data class Failed(val message: String) : SonioxEvent
}

private val json = Json { ignoreUnknownKeys = true }

private fun parseObject(text: String): JsonObject? = try {
    json.parseToJsonElement(text) as? JsonObject
} catch (e: SerializationException) {
    null
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

/** Marker tokens (`<fin>` finalize, `<end>` endpoint): signals, not text. */
private val MARKER_TOKENS = setOf("<fin>", "<end>")

/** Null for malformed messages and any without an `error_code` or a `tokens` array. */
internal fun parseSonioxEvent(text: String): SonioxEvent? {
    val message = parseObject(text) ?: return null
    (message["error_code"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.let {
        return SonioxEvent.Error(it, message.string("error_message"))
    }
    val tokens = message["tokens"] as? JsonArray ?: return null
    return SonioxEvent.Tokens(
        tokens.mapNotNull { element ->
            val token = element as? JsonObject ?: return@mapNotNull null
            val tokenText = token.string("text")?.takeIf { it !in MARKER_TOKENS } ?: return@mapNotNull null
            SonioxToken(tokenText, token.boolean("is_final") == true)
        },
        finished = message.boolean("finished") == true
    )
}

/** The streamed transcript: final tokens appended once, plus the latest response's non-final tail. */
internal class SonioxTranscript {

    private val final = StringBuilder()
    private var tail = ""

    /** Everything heard so far. */
    val liveText: String get() = (final.toString() + tail).trim()

    /** The final tokens only: the transcript once the stream finished. */
    val finalText: String get() = final.toString().trim()

    fun onTokens(tokens: List<SonioxToken>) {
        val nonFinal = StringBuilder()
        tokens.forEach { if (it.isFinal) final.append(it.text) else nonFinal.append(it.text) }
        tail = nonFinal.toString()
    }
}

/** The `id` of a created file or transcription; null if missing. */
internal fun parseSonioxId(body: String): String? = parseObject(body)?.string("id")

/** A transcription job's state: `queued`, `processing`, `completed` or a failure ([isSonioxJobFailed]). */
internal data class SonioxJob(val status: String, val errorMessage: String?)

/** `GET /v1/transcriptions/{id}`; null without a string `status`. */
internal fun parseSonioxJob(body: String): SonioxJob? {
    val job = parseObject(body) ?: return null
    val status = job.string("status") ?: return null
    return SonioxJob(status, job.string("error_message") ?: job.string("error_type"))
}

/** `GET /v1/transcriptions/{id}/transcript`'s `text`; null if missing. */
internal fun parseSonioxTranscriptText(body: String): String? = parseObject(body)?.string("text")

/** The API schema says `error`, the errors page `failed`: both end the job. */
internal fun isSonioxJobFailed(status: String): Boolean = status == "error" || status == "failed"

/** For an error frame's or a REST error's [code] and [message]. */
internal fun sonioxErrorMessage(code: Int, message: String?): String = when {
    code == 401 -> "Soniox rejected the API key. Check it in Iris settings."
    code == 402 -> "Soniox account is out of credits or over budget"
    code == 403 -> "Soniox API key doesn't have permission for this"
    code == 429 -> "Soniox limit reached" + (message?.let { ": $it" } ?: "")
    code >= 500 -> "Soniox server error ($code)"
    else -> message?.let { "Soniox error: $it" } ?: "Soniox request failed ($code)"
}

/**
 * For a non-2xx response (a REST call or a rejected handshake). [body] may be `{status_code, error_type, message,
 * validation_errors: [{error_type, location, message}]}`.
 */
internal fun sonioxHttpErrorMessage(code: Int, body: String): String {
    val error = parseObject(body)
    val message = error?.string("message")?.takeIf { it.isNotBlank() }
        ?: ((error?.get("validation_errors") as? JsonArray)?.firstOrNull() as? JsonObject)?.string("message")
    return sonioxErrorMessage(code, message)
}

/**
 * The stream got no audio before it ended (e.g. stopped at once): blank, so the controller shows "No speech
 * detected". The one error told apart by its message (no `error_type` of its own).
 */
internal fun isNoAudioError(e: SonioxEvent.Error): Boolean =
    e.code == 400 && e.message?.startsWith("No audio received") == true

/** For a server close before the stream finished. */
internal fun sonioxClosedMessage(code: Int, reason: String): String =
    "Soniox closed the connection" + if (reason.isBlank()) " (code $code)" else ": $reason"

/** For a failure without an HTTP response. */
internal fun sonioxNetworkErrorMessage(cause: Throwable): String =
    "Network error: ${cause.message ?: cause.javaClass.simpleName}"
