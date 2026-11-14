/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.stt.soniox

import android.util.Log
import dev.jamius.iris.stt.SpeechToTextProvider
import dev.jamius.iris.stt.SttException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.concurrent.TimeUnit.SECONDS

/**
 * Soniox real-time transcription over a WebSocket (one socket, no reconnect). The start frame goes first, then the
 * audio as raw PCM16 binary frames while recording; when the audio flow completes, an empty text frame ends the
 * stream and the final tokens are the transcript once a `finished` response arrives.
 *
 * @param modelInfo the model's catalogue entry (null if unknown), for the strict-hints check.
 */
class SonioxSpeechToText(
    private val httpClient: OkHttpClient,
    private val apiKey: suspend () -> String?,
    private val modelInfo: suspend (String) -> SonioxModel?,
    private val url: String = SonioxRegion.US.webSocketUrl,
    private val warmUpUrl: String = url,
) : SpeechToTextProvider {

    // OkHttp connects WebSockets on `httpClient.newBuilder().protocols(listOf(HTTP_1_1))`. A pooled connection is
    // only reused for an equal Address (protocols included), so the warm-up must use exactly that client setup.
    private val warmUpClient = httpClient.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()

    // Pings keep the socket alive through long pauses VAD doesn't send (mobile NATs drop idle connections); a missed
    // pong fails the socket. Same pool and Address as `httpClient`, so the warm-up's connection is still reused.
    private val socketClient = httpClient.newBuilder().pingInterval(PING_INTERVAL_SECONDS, SECONDS).build()

    /** `System.nanoTime()` of the last warm-up whose connection is still unused, or null. */
    @Volatile
    private var warmedUpAt: Long? = null

    override fun sampleRate(model: String): Int = SONIOX_SAMPLE_RATE

    /** Billed by wall-clock stream time, and wants audio at real-time pace: a speed-up saves nothing. */
    override val allowsSpeedUp: Boolean get() = false

    override suspend fun transcribe(
        audio: Flow<ByteArray>,
        model: String,
        language: String,
        timeoutMs: Long,
        onPartial: (String) -> Unit,
    ): String {
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: throw SttException("Add your Soniox API key in Iris settings")
        warmedUpAt = null // The handshake takes the warmed connection (an upgraded one never returns to the pool).
        val hints = sonioxLanguageHints(language)
        val events = Channel<SonioxEvent>(Channel.UNLIMITED)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .build()
        val listener = Listener(events, startedAt = System.nanoTime())
        val socket = socketClient.newWebSocket(request, listener)
        var closed = false
        try {
            val text = coroutineScope {
                val final = async { awaitFinal(events, onPartial) }
                // Only with hints: the catalogue lookup may wait for a load (the audio queues in the controller).
                val info = if (hints.isEmpty()) null else async { modelInfo(model) }
                var started = false
                suspend fun sendStart() {
                    if (started) return
                    started = true
                    val strict = info?.await()?.supportsLanguageHintsStrict == true
                    socket.send(sonioxStartMessage(model, hints, strict))
                }
                // Started at the first chunk, so the mic opens at once. OkHttp queues messages until the handshake
                // completes. An empty binary frame is only an empty chunk: skipped.
                audio.collect {
                    if (it.isNotEmpty()) {
                        sendStart()
                        socket.send(it.toByteString())
                    }
                }
                sendStart()
                listener.endSentAt = System.nanoTime()
                socket.send(END_OF_AUDIO)
                // On a slow uplink OkHttp may still be sending queued audio; only time the server once it's sent.
                // A socket failure meanwhile fails `final`, which cancels this wait.
                withTimeoutOrNull(DRAIN_TIMEOUT_MS) { while (socket.queueSize() > 0) delay(DRAIN_POLL_MS) }
                    ?: throw SttException("Timed out sending audio to Soniox")
                withTimeoutOrNull(timeoutMs) { final.await() }
                    ?: throw SttException("Timed out waiting for the transcript")
            }
            // Doesn't wait: only enqueues the close frame, and the close handshake finishes in the background.
            socket.close(NORMAL_CLOSURE, null)
            closed = true
            return text
        } finally {
            // Error or cancellation: drop queued audio and tear the socket down immediately.
            if (!closed) socket.cancel()
        }
    }

    /**
     * `HEAD` on the WebSocket URL without a key: a quick 426 (keep-alive), leaving an idle HTTP/1.1 connection in
     * the pool for the next [transcribe] handshake. Skipped within [WARM_UP_WINDOW_MS] of an earlier warm-up whose
     * connection no session has taken yet.
     */
    override fun warmUp() {
        val now = System.nanoTime()
        warmedUpAt?.let { if (now - it < WARM_UP_WINDOW_MS * 1_000_000) return }
        warmedUpAt = now
        val request = Request.Builder().url(warmUpUrl).head().build()
        warmUpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d(TAG, "Warm-up failed: $e")
                // No connection was left in the pool: let the next call try again (e.g. once back online).
                if (warmedUpAt == now) warmedUpAt = null
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { Log.d(TAG, "Warm-up: ${it.protocol} ${it.code}") }
            }
        })
    }

    private suspend fun awaitFinal(events: ReceiveChannel<SonioxEvent>, onPartial: (String) -> Unit): String {
        val transcript = SonioxTranscript()
        for (event in events) {
            when (event) {
                is SonioxEvent.Tokens -> {
                    transcript.onTokens(event.tokens)
                    onPartial(transcript.liveText)
                    if (event.finished) return transcript.finalText
                }
                is SonioxEvent.Error -> {
                    // Stopped before any audio: blank, so the controller shows "No speech detected".
                    if (isNoAudioError(event)) return ""
                    throw SttException(sonioxErrorMessage(event.code, event.message))
                }
                is SonioxEvent.Failed -> throw SttException(event.message)
            }
        }
        error("Event channel closed") // Never closed: the session ends by returning or being cancelled.
    }

    /** Runs on OkHttp's threads; only forwards events to the session coroutine. */
    private class Listener(
        private val events: SendChannel<SonioxEvent>,
        private val startedAt: Long,
    ) : WebSocketListener() {

        /** `System.nanoTime()` when the end-of-audio frame was sent, or null. */
        @Volatile
        var endSentAt: Long? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "Timing: transcribe->ws open ${(System.nanoTime() - startedAt) / 1_000_000} ms")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = parseSonioxEvent(text) ?: return
            if (event is SonioxEvent.Tokens && event.finished) {
                endSentAt?.let { Log.d(TAG, "Timing: end->finished ${(System.nanoTime() - it) / 1_000_000} ms") }
            }
            events.trySend(event)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // The server closes after `finished` (already handled) or after an error frame (handled first).
            events.trySend(SonioxEvent.Failed(sonioxClosedMessage(code, reason)))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val message = if (response != null) {
                // OkHttp closes the rejected upgrade's response only after this returns.
                val body = try {
                    response.body.string()
                } catch (e: IOException) {
                    ""
                }
                sonioxHttpErrorMessage(response.code, body)
            } else {
                sonioxNetworkErrorMessage(t)
            }
            events.trySend(SonioxEvent.Failed(message))
        }
    }

    private companion object {
        const val TAG = "SonioxStt"
        const val NORMAL_CLOSURE = 1000
        const val DRAIN_TIMEOUT_MS = 60_000L
        const val DRAIN_POLL_MS = 10L
        const val PING_INTERVAL_SECONDS = 30L

        /** Inside OkHttp's 5 min pool keep-alive; stt-rt.soniox.com's idle timeout is unmeasured. */
        const val WARM_UP_WINDOW_MS = 180_000L
    }
}
