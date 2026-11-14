/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.stt.deepgram

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
 * Deepgram live transcription over a WebSocket (one socket, no reconnect). Audio is sent as raw PCM16 binary frames
 * while recording; when the audio flow completes, CloseStream flushes the stream and the settled segments are the
 * final transcript once Metadata (or the server's normal close) arrives.
 */
class DeepgramSpeechToText(
    private val httpClient: OkHttpClient,
    private val apiKey: suspend () -> String?,
    private val url: String = LISTEN_URL,
    private val warmUpUrl: String = LISTEN_URL,
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

    override fun sampleRate(model: String): Int = DEEPGRAM_SAMPLE_RATE

    override suspend fun transcribe(
        audio: Flow<ByteArray>,
        model: String,
        language: String,
        timeoutMs: Long,
        onPartial: (String) -> Unit,
    ): String {
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: throw SttException("Add your Deepgram API key in Iris settings")
        warmedUpAt = null // The handshake takes the warmed connection (an upgraded one never returns to the pool).
        val events = Channel<DeepgramEvent>(Channel.UNLIMITED)
        val request = Request.Builder()
            .url(listenUrl(url, model, language, streaming = true))
            .header("Authorization", "Token $key")
            .build()
        val listener = Listener(events, startedAt = System.nanoTime())
        val socket = socketClient.newWebSocket(request, listener)
        var closed = false
        try {
            val text = coroutineScope {
                val final = async { awaitFinal(events, onPartial) }
                // OkHttp queues messages until the handshake completes. An empty binary frame closes the stream.
                audio.collect { if (it.isNotEmpty()) socket.send(it.toByteString()) }
                listener.closeStreamSentAt = System.nanoTime()
                socket.send(CLOSE_STREAM)
                // On a slow uplink OkHttp may still be sending queued audio; only time the server once it's sent.
                // A socket failure meanwhile fails `final`, which cancels this wait.
                withTimeoutOrNull(DRAIN_TIMEOUT_MS) { while (socket.queueSize() > 0) delay(DRAIN_POLL_MS) }
                    ?: throw SttException("Timed out sending audio to Deepgram")
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
     * `HEAD /v1/listen` without a key: a quick 405 with an empty body, leaving an idle HTTP/1.1 connection in the
     * pool for the next [transcribe] handshake. Skipped within [WARM_UP_WINDOW_MS] of an earlier warm-up whose
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

    private suspend fun awaitFinal(events: ReceiveChannel<DeepgramEvent>, onPartial: (String) -> Unit): String {
        val transcript = DeepgramTranscript()
        for (event in events) {
            when (event) {
                is DeepgramEvent.Result -> {
                    transcript.onResult(event.isFinal, event.transcript)
                    onPartial(transcript.liveText)
                }
                DeepgramEvent.Metadata -> return transcript.finalText
                is DeepgramEvent.Error -> Log.w(TAG, "Deepgram error: ${event.message}")
                is DeepgramEvent.Failed -> throw SttException(event.message)
            }
        }
        error("Event channel closed") // Never closed: the session ends by returning or being cancelled.
    }

    /** Runs on OkHttp's threads; only forwards events to the session coroutine. */
    private class Listener(
        private val events: SendChannel<DeepgramEvent>,
        private val startedAt: Long,
    ) : WebSocketListener() {

        /** `System.nanoTime()` when CloseStream was sent, or null; only after it do Metadata or a close end it. */
        @Volatile
        var closeStreamSentAt: Long? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "Timing: transcribe->ws open ${(System.nanoTime() - startedAt) / 1_000_000} ms")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = parseDeepgramEvent(text) ?: return
            if (event == DeepgramEvent.Metadata) {
                val sentAt = closeStreamSentAt ?: return
                Log.d(TAG, "Timing: CloseStream->Metadata ${(System.nanoTime() - sentAt) / 1_000_000} ms")
            }
            events.trySend(event)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // After CloseStream the server closes normally once everything is sent (Metadata normally came first).
            val finished = code == NORMAL_CLOSURE && closeStreamSentAt != null
            events.trySend(
                if (finished) DeepgramEvent.Metadata else DeepgramEvent.Failed(deepgramClosedMessage(code, reason))
            )
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val message = if (response != null) {
                // OkHttp closes the rejected upgrade's response only after this returns.
                val body = try {
                    response.body.string()
                } catch (e: IOException) {
                    ""
                }
                deepgramHttpErrorMessage(response.code, body, response.header("dg-error"))
            } else {
                deepgramNetworkErrorMessage(t)
            }
            events.trySend(DeepgramEvent.Failed(message))
        }
    }

    private companion object {
        const val TAG = "DeepgramStt"
        const val NORMAL_CLOSURE = 1000
        const val DRAIN_TIMEOUT_MS = 60_000L
        const val DRAIN_POLL_MS = 10L
        const val PING_INTERVAL_SECONDS = 30L

        /** Inside OkHttp's 5 min pool keep-alive; api.deepgram.com's idle timeout is unmeasured. */
        const val WARM_UP_WINDOW_MS = 180_000L
    }
}
