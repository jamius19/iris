/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.stt.openai

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
import java.io.IOException
import java.util.concurrent.TimeUnit.SECONDS

/**
 * OpenAI Realtime transcription-only session over a WebSocket. Audio is appended as it is recorded; when the
 * audio flow completes the buffer is committed and the session waits for that item's final transcript.
 */
class OpenAiRealtimeSpeechToText(
    private val httpClient: OkHttpClient,
    private val apiKey: suspend () -> String?,
    private val url: String = REALTIME_URL,
    private val warmUpUrl: String = WARM_UP_URL,
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

    override fun liveText(model: String): Boolean = hasLiveText(model)

    override suspend fun transcribe(
        audio: Flow<ByteArray>,
        model: String,
        language: String,
        timeoutMs: Long,
        onPartial: (String) -> Unit,
    ): String {
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: throw SttException("Add your OpenAI API key in Iris settings")
        warmedUpAt = null // The handshake takes the warmed connection (an upgraded one never returns to the pool).
        val events = Channel<RealtimeEvent>(Channel.UNLIMITED)
        val request = Request.Builder().url(url).header("Authorization", "Bearer $key").build()
        val socket = socketClient.newWebSocket(request, Listener(events, startedAt = System.nanoTime()))
        var closed = false
        try {
            val text = coroutineScope {
                val final = async { awaitFinal(events, onPartial) }
                // OkHttp queues messages until the handshake completes.
                socket.send(sessionUpdate(model, language))
                var sentBytes = 0
                audio.collect {
                    socket.send(appendAudio(it))
                    sentBytes += it.size
                }
                // The server rejects a commit of under 100 ms of audio (e.g. an instant stop); pad it with silence.
                if (sentBytes < MIN_COMMIT_BYTES) socket.send(appendAudio(ByteArray(MIN_COMMIT_BYTES - sentBytes)))
                socket.send(COMMIT_AUDIO)
                // On a slow uplink OkHttp may still be sending queued audio; only time the server once it's sent.
                // A socket failure meanwhile fails `final`, which cancels this wait.
                withTimeoutOrNull(DRAIN_TIMEOUT_MS) { while (socket.queueSize() > 0) delay(DRAIN_POLL_MS) }
                    ?: throw SttException("Timed out sending audio to OpenAI")
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
     * `HEAD /v1/models` without a key: a quick 401 with an empty body, leaving an idle HTTP/1.1 connection in the
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

    private suspend fun awaitFinal(events: ReceiveChannel<RealtimeEvent>, onPartial: (String) -> Unit): String {
        val transcript = TranscriptAssembler()
        for (event in events) {
            when (event) {
                is RealtimeEvent.Delta -> {
                    transcript.onDelta(event.itemId, event.delta)
                    onPartial(transcript.liveText)
                }
                is RealtimeEvent.Completed -> {
                    transcript.onCompleted(event.itemId, event.transcript)
                    onPartial(transcript.liveText)
                }
                is RealtimeEvent.Committed -> transcript.onCommitted(event.itemId)
                is RealtimeEvent.Failed -> throw SttException(event.message)
            }
            transcript.finalText?.let { return it }
        }
        error("Event channel closed") // Never closed: the session ends by returning or being cancelled.
    }

    /** Runs on OkHttp's threads; only forwards events to the session coroutine. */
    private class Listener(
        private val events: SendChannel<RealtimeEvent>,
        private val startedAt: Long,
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "Timing: transcribe->ws open ${(System.nanoTime() - startedAt) / 1_000_000} ms")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            parseServerEvent(text)?.let(events::trySend)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            events.trySend(RealtimeEvent.Failed(closedMessage(reason)))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            events.trySend(RealtimeEvent.Failed(connectionFailureMessage(response?.code, t)))
        }
    }

    private companion object {
        const val TAG = "OpenAiRealtimeStt"
        const val NORMAL_CLOSURE = 1000
        const val DRAIN_TIMEOUT_MS = 60_000L
        const val DRAIN_POLL_MS = 10L
        const val PING_INTERVAL_SECONDS = 30L

        /** Inside both OkHttp's 5 min pool keep-alive and api.openai.com's 400 s idle timeout (measured). */
        const val WARM_UP_WINDOW_MS = 180_000L
    }
}
