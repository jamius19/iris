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

internal const val COMMIT_AUDIO = """{"type":"input_audio_buffer.commit"}"""

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
                    // gpt-live-transcribe takes the `languages` array; never also send singular `language`.
                    if (language.isNotBlank()) putJsonArray("languages") { add(language) }
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
    401 -> "OpenAI rejected the API key. Check it in Iris settings."
    else -> "Couldn't connect to OpenAI (HTTP $httpCode)"
}

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
