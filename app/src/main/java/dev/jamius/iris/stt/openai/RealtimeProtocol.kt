/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.stt.openai

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

// Message building and parsing for OpenAI Realtime transcription-only sessions, free of I/O for unit tests.
// https://developers.openai.com/api/docs/guides/realtime-transcription
// https://developers.openai.com/api/reference/resources/realtime/client-events
// https://developers.openai.com/api/reference/resources/realtime/server-events

/** Transcription intent, no `model` query param: a model in the URL would create a conversation session. */
internal const val REALTIME_URL = "wss://api.openai.com/v1/realtime?intent=transcription"

/** Warm-up target: answers a keyless `HEAD` with a quick 401 and no body. */
internal const val WARM_UP_URL = "https://api.openai.com/v1/models"

/** The least audio the server accepts in a commit: 100 ms of PCM16 mono 24 kHz. */
internal const val MIN_COMMIT_BYTES = 24_000 * 2 / 10

internal const val COMMIT_AUDIO = """{"type":"input_audio_buffer.commit"}"""

/**
 * The models a Realtime transcription session accepts (`audio.input.transcription.model`, client events reference,
 * checked 2026-11-12). Only gpt-live-transcribe and gpt-realtime-whisper stream text before the commit; the others
 * transcribe the committed audio.
 */
internal val REALTIME_TRANSCRIPTION_MODELS = setOf(
    "whisper-1",
    "gpt-transcribe",
    "gpt-live-transcribe",
    "gpt-4o-mini-transcribe",
    "gpt-4o-mini-transcribe-2025-12-15",
    "gpt-4o-transcribe",
    "gpt-4o-transcribe-diarize",
    "gpt-realtime-whisper",
)

/** [model] streams over a Realtime session; any other id is uploaded after stop. */
internal fun isRealtimeTranscriptionModel(model: String): Boolean = model.trim() in REALTIME_TRANSCRIPTION_MODELS

/** [model] reports text while recording; the other Realtime models transcribe the committed buffer at stop. */
internal fun hasLiveText(model: String): Boolean = model.trim() in setOf("gpt-live-transcribe", "gpt-realtime-whisper")

/** [model] takes the `languages` array; the others take singular `language` (never both). */
internal fun usesLanguageList(model: String): Boolean = model.trim() in setOf("gpt-transcribe", "gpt-live-transcribe")

/** PCM16 mono 24 kHz input, no turn detection (gpt-live-transcribe supports no VAD; we commit on stop). */
internal fun sessionUpdate(model: String, language: String): String = buildJsonObject {
    put("type", "session.update")
    putJsonObject("session") {
        put("type", "transcription")
        putJsonObject("audio") {
            putJsonObject("input") {
                putJsonObject("format") {
                    put("type", "audio/pcm")
                    put("rate", 24_000)
                }
                putJsonObject("transcription") {
                    put("model", model)
                    when {
                        language.isBlank() -> Unit
                        usesLanguageList(model) -> putJsonArray("languages") { add(language) }
                        else -> put("language", language)
                    }
                }
                put("turn_detection", JsonNull)
            }
        }
    }
}.toString()

internal fun appendAudio(chunk: ByteArray): String = buildJsonObject {
    put("type", "input_audio_buffer.append")
    put("audio", Base64.getEncoder().encodeToString(chunk))
}.toString()

/** The server events (and socket outcomes) the client acts on. */
internal sealed interface RealtimeEvent {
    data class Delta(val itemId: String, val delta: String) : RealtimeEvent
    data class Completed(val itemId: String, val transcript: String) : RealtimeEvent
    data class Committed(val itemId: String) : RealtimeEvent

    /** Ends the session; [message] is user-readable. */
    data class Failed(val message: String) : RealtimeEvent
}

private val json = Json { ignoreUnknownKeys = true }

/** Returns null for events the client ignores (session.created, session.updated, ...) and malformed ones. */
internal fun parseServerEvent(text: String): RealtimeEvent? {
    val event = try {
        json.parseToJsonElement(text) as? JsonObject
    } catch (e: SerializationException) {
        null
    } ?: return null
    val itemId = event.string("item_id")
    return when (event.string("type")) {
        "conversation.item.input_audio_transcription.delta" ->
            itemId?.let { RealtimeEvent.Delta(it, event.string("delta").orEmpty()) }
        "conversation.item.input_audio_transcription.completed" ->
            itemId?.let { RealtimeEvent.Completed(it, event.string("transcript").orEmpty()) }
        "input_audio_buffer.committed" -> itemId?.let(RealtimeEvent::Committed)
        "conversation.item.input_audio_transcription.failed" ->
            RealtimeEvent.Failed("Transcription failed: ${event.errorMessage()}")
        "error" -> RealtimeEvent.Failed("OpenAI error: ${event.errorMessage()}")
        else -> null
    }
}

/** For a WebSocket failure; [httpCode] is set when the handshake got an HTTP error response. */
internal fun connectionFailureMessage(httpCode: Int?, cause: Throwable): String = when (httpCode) {
    null -> "Network error: ${cause.message ?: cause.javaClass.simpleName}"
    401 -> API_KEY_REJECTED
    else -> "Couldn't connect to OpenAI (HTTP $httpCode)"
}

/** For a non-2xx response; [body] may carry `{ "error": { "message": ... } }`. */
internal fun openAiHttpErrorMessage(code: Int, body: String): String = when {
    code == 401 || code == 403 -> API_KEY_REJECTED
    code == 429 -> "OpenAI rate limit or quota reached"
    code >= 500 -> "OpenAI server error (HTTP $code)"
    else -> errorMessageOf(body)?.let { "OpenAI error: $it" } ?: "OpenAI request failed (HTTP $code)"
}

private fun errorMessageOf(body: String): String? = try {
    ((json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonObject)?.string("message")
} catch (e: SerializationException) {
    null
}

private const val API_KEY_REJECTED = "OpenAI rejected the API key. Check it in Iris settings."

internal fun closedMessage(reason: String): String =
    "OpenAI closed the connection" + if (reason.isBlank()) "" else ": $reason"

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.errorMessage(): String = (this["error"] as? JsonObject)?.string("message") ?: "unknown error"

/**
 * Assembles transcript text from per-item events. Deltas are appended per `item_id`; a completed transcript
 * replaces that item's deltas. Items are joined with spaces in first-seen order (completion order across items
 * isn't guaranteed). We commit once, on stop, so normally there is a single item.
 */
internal class TranscriptAssembler {

    private class Item(var text: String = "", var completed: Boolean = false)

    private val items = LinkedHashMap<String, Item>()
    private var committedItemId: String? = null

    /** Everything heard so far, including items still streaming deltas. */
    val liveText: String get() = join(items.values)

    /**
     * Completed items once the committed item has completed, else null. Only completed items count, so text from
     * an item that never completes can't end up in the final transcript.
     */
    val finalText: String?
        get() {
            val committed = committedItemId?.let(items::get) ?: return null
            return if (committed.completed) join(items.values.filter { it.completed }) else null
        }

    fun onDelta(itemId: String, delta: String) {
        val item = items.getOrPut(itemId, ::Item)
        if (!item.completed) item.text += delta
    }

    fun onCompleted(itemId: String, transcript: String) {
        val item = items.getOrPut(itemId, ::Item)
        item.text = transcript
        item.completed = true
    }

    fun onCommitted(itemId: String) {
        committedItemId = itemId
    }

    private fun join(items: Collection<Item>): String =
        items.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
}
