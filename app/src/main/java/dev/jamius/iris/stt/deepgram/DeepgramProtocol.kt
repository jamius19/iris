/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.stt.deepgram

import dev.jamius.iris.stt.vercel.joined
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

// Request building and message parsing for Deepgram's listen API, free of I/O for unit tests. Checked 2026-11-13:
// https://developers.deepgram.com/reference/speech-to-text/listen-streaming
// https://developers.deepgram.com/reference/speech-to-text/listen-pre-recorded
// https://developers.deepgram.com/docs/close-stream, https://developers.deepgram.com/docs/errors

/**
 * Streaming (OkHttp upgrades `https` to a WebSocket: `wss` on the wire) and pre-recorded upload (`POST`) share it.
 * A keyless `HEAD` gets a quick 405 with no body: the warm-up target.
 */
internal const val LISTEN_URL = "https://api.deepgram.com/v1/listen"

/** The model menu's list; any other id can be typed. */
internal val DEEPGRAM_MODELS = listOf("nova-3", "nova-3-medical", "nova-2")

/** PCM16 mono rate of both modes: half the upload of 24 kHz, and the VAD's own rate. */
internal const val DEEPGRAM_SAMPLE_RATE = 16_000

/** Flushes the stream: the server sends the remaining Results, then Metadata, then closes. */
internal const val CLOSE_STREAM = """{"type":"CloseStream"}"""

/**
 * [baseUrl] with the query for [model] and [language] (blank = `multi`: Nova-3 code-switching auto-detect). A
 * [streaming] session also states the raw audio format and asks for interim results; `multi` gets the 100 ms
 * endpointing the docs recommend for code-switching. An upload is a WAV file, whose header states the format.
 */
internal fun listenUrl(baseUrl: String, model: String, language: String, streaming: Boolean): HttpUrl {
    val multi = language.isBlank()
    return baseUrl.toHttpUrl().newBuilder()
        .addQueryParameter("model", model)
        .addQueryParameter("language", if (multi) "multi" else language)
        .addQueryParameter("smart_format", "true")
        .addQueryParameter("punctuate", "true")
        .apply {
            if (streaming) {
                addQueryParameter("encoding", "linear16")
                addQueryParameter("sample_rate", DEEPGRAM_SAMPLE_RATE.toString())
                addQueryParameter("channels", "1")
                addQueryParameter("interim_results", "true")
                if (multi) addQueryParameter("endpointing", "100")
            }
        }
        .build()
}

/** The server messages (and socket outcomes) the streaming client acts on. */
internal sealed interface DeepgramEvent {
    /** [isFinal]: a settled segment; else an interim guess the next Result replaces. */
    data class Result(val isFinal: Boolean, val transcript: String) : DeepgramEvent

    /** Sent last, after CloseStream. */
    data object Metadata : DeepgramEvent

    /** Non-fatal per the docs (e.g. an unparseable client message): fatal failures close the socket. */
    data class Error(val message: String) : DeepgramEvent

    /** Ends the session; [message] is user-readable. Not parsed: from the socket's failure or close. */
    data class Failed(val message: String) : DeepgramEvent
}

private val json = Json { ignoreUnknownKeys = true }

private fun parseObject(text: String): JsonObject? = try {
    json.parseToJsonElement(text) as? JsonObject
} catch (e: SerializationException) {
    null
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.firstObject(key: String): JsonObject? = (this[key] as? JsonArray)?.firstOrNull() as? JsonObject

/** The first alternative's transcript of a `{channel: {alternatives: [...]}}`-shaped object. */
private fun JsonObject.transcript(): String? =
    (this["channel"] as? JsonObject)?.firstObject("alternatives")?.string("transcript")

/** Null for messages the client ignores (SpeechStarted, UtteranceEnd, ...) and malformed ones. */
internal fun parseDeepgramEvent(text: String): DeepgramEvent? {
    val message = parseObject(text) ?: return null
    return when (message.string("type")) {
        "Results" -> message.transcript()?.let {
            DeepgramEvent.Result((message["is_final"] as? JsonPrimitive)?.booleanOrNull == true, it)
        }
        "Metadata" -> DeepgramEvent.Metadata
        "Error" -> DeepgramEvent.Error(message.string("description") ?: message.string("message") ?: "unknown error")
        else -> null
    }
}

/** `results.channels[0].alternatives[0].transcript` of a pre-recorded response; null if the body isn't that shape. */
internal fun parseListenResponse(body: String): String? =
    (parseObject(body)?.get("results") as? JsonObject)?.firstObject("channels")?.firstObject("alternatives")
        ?.string("transcript")

/** The streamed transcript: settled segments (`is_final`) joined, plus the latest interim for the live text. */
internal class DeepgramTranscript {

    /** The settled segments only: the final transcript. */
    var finalText = ""
        private set

    private var interim = ""

    /** Everything heard so far. */
    val liveText: String get() = joined(finalText, interim)

    fun onResult(isFinal: Boolean, transcript: String) {
        val text = transcript.trim()
        if (isFinal) {
            if (text.isNotEmpty()) finalText = joined(finalText, text)
            interim = ""
        } else {
            interim = text
        }
    }
}

/**
 * For a non-2xx response (a rejected handshake or upload). [body] may carry `{err_code, err_msg}` or, from newer
 * endpoints, `{category, message}`; [dgError] is the `dg-error` header with the same message.
 */
internal fun deepgramHttpErrorMessage(code: Int, body: String, dgError: String?): String = when {
    code == 401 -> "Deepgram rejected the API key. Check it in Iris settings."
    code == 402 -> "Deepgram account is out of credits"
    code == 429 -> "Deepgram rate limit reached"
    code >= 500 -> "Deepgram server error (HTTP $code)"
    else -> {
        val message = parseObject(body)?.let { it.string("err_msg") ?: it.string("message") }
            ?: dgError?.takeIf { it.isNotBlank() }
        message?.let { "Deepgram error: $it" } ?: "Deepgram request failed (HTTP $code)"
    }
}

/** For a server close that isn't the end of a finished stream (e.g. `1011` with `NET-0001`). */
internal fun deepgramClosedMessage(code: Int, reason: String): String =
    "Deepgram closed the connection" + if (reason.isBlank()) " (code $code)" else ": $reason"

/** For a failure without an HTTP response. */
internal fun deepgramNetworkErrorMessage(cause: Throwable): String =
    "Network error: ${cause.message ?: cause.javaClass.simpleName}"
