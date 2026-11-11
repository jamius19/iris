/*
* Author: Jamius Siam
* Since: 12/11/2026
*/

package dev.jamius.iris.stt.vercel

import dev.jamius.iris.cleanup.GatewayModel
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

// Frame building and parsing for Vercel AI Gateway's streaming transcription WebSocket (beta), free of I/O for unit
// tests. No public docs: the protocol follows the AI SDK source (`@ai-sdk/gateway` 4.0.143
// `gateway-transcription-model.ts`, `gateway-realtime-auth.ts`; `@ai-sdk/provider-utils`
// `transcription-stream-envelope.ts`) and Handy's `vercel_live_transcription.rs`.

/** Upgraded to a WebSocket by OkHttp (`https` here is `wss` on the wire). */
internal const val TRANSCRIPTION_URL = "https://ai-gateway.vercel.sh/v4/ai/transcription-model"

/** Warm-up target: the public model catalog answers a keyless `HEAD` quickly with no body. */
internal const val GATEWAY_WARM_UP_URL = "https://ai-gateway.vercel.sh/v1/models"

/** Marker subprotocol of a transcription stream handshake. */
internal const val SUBPROTOCOL = "ai-gateway-transcription.v1"

/** `ai-gateway-protocol-version` the AI SDK sends. */
internal const val PROTOCOL_VERSION = "0.0.1"

/** `ai-transcription-model-specification-version` the AI SDK sends. */
internal const val SPECIFICATION_VERSION = "4"

internal const val AUDIO_DONE = """{"type":"transcription-stream.audio-done"}"""

/** The catalog tag of models that stream over the WebSocket. */
internal const val STREAMING_TAG = "websocket-transcription"

/** A catalog model that streams over this WebSocket. */
internal fun isStreamingTranscriptionModel(model: GatewayModel): Boolean =
    model.type == "transcription" && STREAMING_TAG in model.tags

/** [baseUrl] with the model in `?ai-model-id=` (its `/` encoded). */
internal fun streamUrl(baseUrl: String, model: String): HttpUrl =
    baseUrl.toHttpUrl().newBuilder().addQueryParameter("ai-model-id", model).build()

/** [model]'s provider: its id prefix; the Gateway qualifies a bare id as `openai/<id>`. */
private fun gatewayProvider(model: String): String = if ('/' in model) model.substringBefore('/') else "openai"

/** OpenAI's realtime models take 24 kHz PCM16; the others get 16 kHz (as Handy sends them). */
internal fun gatewaySampleRate(model: String): Int = if (gatewayProvider(model) == "openai") 24_000 else 16_000

/**
 * The language option for [model]'s provider (its id prefix; the AI SDK provider names the Gateway passes options
 * on to), and Grok's interim results. Null when there is nothing to send; a provider without a known language
 * option detects the language itself.
 */
internal fun providerOptions(model: String, language: String): JsonObject? {
    val provider = gatewayProvider(model)
    val code = language.trim()
    val options = buildJsonObject {
        if (code.isNotEmpty()) {
            when (provider) {
                "openai", "mistral" -> putJsonObject(provider) { put("language", code) }
                "elevenlabs" -> putJsonObject(provider) { put("languageCode", code) }
                "google" -> putJsonObject(provider) { putJsonArray("languageCodes") { add(code) } }
            }
        }
        // Grok STT streams only ~3 s chunks unless asked for interim results. The Gateway names the model
        // `spacexai/...` while the AI SDK adapter reads `xai`, so it goes under both.
        if (provider == "xai" || provider == "spacexai") {
            for (key in listOf("xai", "spacexai")) {
                putJsonObject(key) { putJsonObject("streaming") { put("interimResults", true) } }
            }
        }
    }
    return options.takeIf { it.isNotEmpty() }
}

/** The first frame: raw PCM16 at [rate], plus [options] when set. */
internal fun startFrame(rate: Int, options: JsonObject?): String = buildJsonObject {
    put("type", "transcription-stream.start")
    putJsonObject("inputAudioFormat") {
        put("type", "audio/pcm")
        put("rate", rate)
    }
    options?.let { put("providerOptions", it) }
}.toString()

/** The server parts the client acts on. */
internal sealed interface GatewayPart {
    /** [warnings]: e.g. unsupported provider options, as JSON. */
    data class StreamStart(val warnings: List<String>) : GatewayPart

    /** Append-only text of the current segment. */
    data class Delta(val delta: String) : GatewayPart

    /** The current segment's text so far; later parts may revise it. */
    data class Partial(val text: String, val startSecond: Double?, val durationInSeconds: Double?) : GatewayPart

    /** A settled segment. */
    data class Final(val text: String) : GatewayPart

    /** The whole transcript; the server closes after it. */
    data class Finish(val text: String) : GatewayPart

    /** Ends the stream; [message] is user-readable. */
    data class Error(val message: String) : GatewayPart
}

private val json = Json { ignoreUnknownKeys = true }

/**
 * One server text frame (a JSON part). Null for unknown types (`response-metadata`, `raw`, ...), malformed JSON and
 * parts missing a required field, which the envelope says to ignore.
 */
internal fun parseGatewayPart(text: String): GatewayPart? {
    val part = try {
        json.parseToJsonElement(text) as? JsonObject
    } catch (e: SerializationException) {
        null
    } ?: return null
    return when (part.string("type")) {
        "stream-start" -> GatewayPart.StreamStart((part["warnings"] as? JsonArray)?.map { it.toString() }.orEmpty())
        "transcript-delta" -> part.string("delta")?.let(GatewayPart::Delta)
        "transcript-partial" -> part.string("text")?.let {
            GatewayPart.Partial(it, part.number("startSecond"), part.number("durationInSeconds"))
        }
        "transcript-final" -> part.string("text")?.let(GatewayPart::Final)
        // Lenient: a dropped finish would fail the stream; a blank text falls back to the live text.
        "finish" -> GatewayPart.Finish(part.string("text").orEmpty())
        "error" -> part["error"]?.let { GatewayPart.Error(errorPartMessage(it)) }
        else -> null
    }
}

/** An `error` part's payload: `{ name, message }` or a string. */
internal fun errorPartMessage(error: JsonElement): String {
    val message = (error as? JsonObject)?.string("message")
        ?: (error as? JsonPrimitive)?.takeIf { it.isString }?.content
    return if (message.isNullOrBlank()) "AI Gateway error" else "AI Gateway error: $message"
}

internal fun gatewayClosedMessage(reason: String): String =
    "AI Gateway closed the connection" + if (reason.isBlank()) "" else ": $reason"

/** For a socket failure without an HTTP response. */
internal fun gatewayNetworkErrorMessage(cause: Throwable): String =
    "Network error: ${cause.message ?: cause.javaClass.simpleName}"

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.number(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

/** How far apart two reported times may be and still count as the same. */
private const val CHUNK_TOLERANCE_SECONDS = 0.05

/**
 * The live text of a stream (ported from Handy): the settled segments, and the segment still being heard as its
 * deltas, its earlier partial chunks and the latest partial. Providers differ: Gemini's partial is the whole segment
 * so far (its deltas then repeat it), Microsoft MAI's only what follows the deltas, and Grok STT sends each ~3 s
 * chunk of an utterance as its own partial.
 */
internal class LiveTranscript {

    private var settled = ""
    private var deltas = ""

    /** Partials a later one followed on from rather than revised. */
    private var chunks = ""
    private var partial = ""

    /** Where the latest partial starts and ends in the audio, when the part says. */
    private var partialSpan: Pair<Double, Double>? = null

    /** Everything heard so far. */
    val text: String
        get() {
            val current = if (partial.trimStart().startsWith(deltas.trim())) partial else deltas + partial
            return joined(settled, joined(chunks, current.trim()).trim())
        }

    /** A delta supersedes the partial before it. */
    fun onDelta(delta: String) {
        deltas += delta
        partial = ""
    }

    /**
     * A partial revises the one before it, unless it starts later, where that one ended (a new Grok chunk): then the
     * earlier one is kept until the segment's final.
     */
    fun onPartial(text: String, startSecond: Double?, durationInSeconds: Double?) {
        val span = partialSpan
        if (startSecond != null && span != null &&
            startSecond > span.first + CHUNK_TOLERANCE_SECONDS && startSecond >= span.second - CHUNK_TOLERANCE_SECONDS
        ) {
            chunks = joined(chunks, partial.trim())
        }
        partial = text
        partialSpan = if (startSecond != null && durationInSeconds != null) {
            startSecond to startSecond + durationInSeconds
        } else {
            null
        }
    }

    /** Settles the current segment as [text]. */
    fun onFinal(text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) settled = joined(settled, trimmed)
        deltas = ""
        chunks = ""
        partial = ""
        partialSpan = null
    }
}

/** [a] and [b] joined with a space, unless either is empty or the boundary is CJK/kana/full-width. */
internal fun joined(a: String, b: String): String {
    if (a.isEmpty() || b.isEmpty() || isUnspaced(a.last()) || isUnspaced(b.first())) return a + b
    return "$a $b"
}

private fun isUnspaced(c: Char): Boolean =
    c in '\u3000'..'\u30ff' || c in '\u3400'..'\u4dbf' || c in '\u4e00'..'\u9fff' ||
        c in '\uf900'..'\ufaff' || c in '\uff00'..'\uffef'

/**
 * Whether [next] only cuts words off the end of [shown]. Such text isn't shown while streaming, as the cut words
 * come straight back (Grok ends a chunk short of its earlier guess; MAI sends settled words as a delta just before
 * the partial with the rest).
 */
internal fun onlyTrims(shown: String, next: String): Boolean = next.length < shown.length && shown.startsWith(next)
