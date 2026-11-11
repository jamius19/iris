/*
* Author: Jamius Siam
* Since: 12/11/2026
*/

package dev.jamius.iris.stt.vercel

import android.util.Log
import dev.jamius.iris.cleanup.vercel.httpErrorMessage
import dev.jamius.iris.stt.SpeechToTextProvider
import dev.jamius.iris.stt.SttException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
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

/**
 * Vercel AI Gateway streaming transcription (beta) over a WebSocket: a start frame, then the recording as raw PCM16
 * binary frames, then `audio-done`; the server streams transcript parts and ends with `finish`. A stream that fails
 * after connecting (dropped, error part, closed without `finish`, stalled) is reconnected once and sent all audio
 * again, within a deadline of [reconnectTimeoutMs] plus time to resend the audio.
 */
class VercelGatewaySpeechToText(
    private val httpClient: OkHttpClient,
    private val apiKey: suspend () -> String?,
    private val url: String = TRANSCRIPTION_URL,
    private val warmUpUrl: String = GATEWAY_WARM_UP_URL,
    private val stallTimeoutMs: Long = STALL_TIMEOUT_MS,
    private val reconnectTimeoutMs: Long = RECONNECT_TIMEOUT_MS,
    private val reconnectDelayMs: Long = RECONNECT_DELAY_MS,
) : SpeechToTextProvider {

    // OkHttp connects WebSockets on `httpClient.newBuilder().protocols(listOf(HTTP_1_1))`. A pooled connection is
    // only reused for an equal Address (protocols included), so the warm-up must use exactly that client setup.
    private val warmUpClient = httpClient.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()

    /** `System.nanoTime()` of the last warm-up whose connection is still unused, or null. */
    @Volatile
    private var warmedUpAt: Long? = null

    override fun sampleRate(model: String): Int = gatewaySampleRate(model)

    override suspend fun transcribe(
        audio: Flow<ByteArray>,
        model: String,
        language: String,
        onPartial: (String) -> Unit,
    ): String {
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: throw SttException("Add your Vercel AI Gateway API key in Iris settings")
        val request = Request.Builder()
            .url(streamUrl(url, model))
            .header("Authorization", "Bearer $key")
            // Only the marker: the key rides the header above, never a subprotocol the server may echo. The AI SDK
            // also offers `ai-gateway-auth.<key>` here, for browsers that can't set headers.
            .header("Sec-WebSocket-Protocol", SUBPROTOCOL)
            .header("ai-gateway-protocol-version", PROTOCOL_VERSION)
            .header("ai-gateway-auth-method", "api-key")
            .header("ai-transcription-model-specification-version", SPECIFICATION_VERSION)
            .apply {
                try {
                    header("ai-model-id", model)
                } catch (e: IllegalArgumentException) {
                    throw SttException("Invalid model id: $model")
                }
            }
            .build()
        warmedUpAt = null // The handshake takes the warmed connection (an upgraded one never returns to the pool).
        val rate = sampleRate(model)
        val session = Session(request, model, rate, startFrame(rate, providerOptions(model, language)), onPartial)
        var closed = false
        try {
            val text = session.run(audio)
            // Doesn't wait: only enqueues the close frame, and the close handshake finishes in the background.
            session.socket.close(NORMAL_CLOSURE, null)
            closed = true
            return text
        } finally {
            // Error or cancellation: drop queued audio and tear the socket down immediately.
            if (!closed) session.socket.cancel()
        }
    }

    /**
     * `HEAD` of the public model catalog without a key, leaving an idle HTTP/1.1 connection in the pool for the next
     * [transcribe] handshake. Skipped within [WARM_UP_WINDOW_MS] of an earlier warm-up whose connection no session
     * has taken yet.
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

    /**
     * One transcription: a single loop over [inputs], fed by the audio collector, each socket's listener (tagged
     * with its [attempt], so a replaced socket's late events are ignored) and the drain wait.
     */
    private inner class Session(
        private val request: Request,
        private val model: String,
        private val rate: Int,
        private val start: String,
        private val onPartial: (String) -> Unit,
    ) {
        private val inputs = Channel<Input>(Channel.UNLIMITED)
        private val startedAt = System.nanoTime()

        /** Every chunk sent so far, to send again to a new stream. */
        private val sent = ArrayList<ByteArray>()
        private var sentBytes = 0
        private var attempt = 0
        private var listener = Listener(attempt, inputs, startedAt)
        var socket: WebSocket = connect()
            private set

        private var stopped = false
        private var stoppedAt = 0L

        /** The current stream has sent all audio and `audio-done`: the stall timeout runs. */
        private var drained = false
        private var drainJob: Job? = null
        private var retried = false

        /** `System.nanoTime()` by which the reconnect (handshake retries, resend, drain if stopped) must be done. */
        private var reconnectDeadline = 0L
        private var transcript = LiveTranscript()

        /** The text last passed to [onPartial]. */
        private var shown = ""

        suspend fun run(audio: Flow<ByteArray>): String = coroutineScope {
            launch {
                audio.collect { inputs.send(Input.Audio(it)) }
                inputs.send(Input.AudioEnd)
            }
            val text = receive(this)
            coroutineContext.cancelChildren() // The drain wait, or audio if the server finished early.
            text
        }

        private suspend fun receive(scope: CoroutineScope): String {
            while (true) {
                val input = if (drained) withTimeoutOrNull(stallTimeoutMs) { inputs.receive() } else inputs.receive()
                if (input == null) {
                    // A silently dead connection (no ping, no read timeout on WebSockets) only shows up here.
                    fail(STALL_MESSAGE, retryable = true, scope)
                    continue
                }
                when (input) {
                    is Input.Audio -> {
                        sent += input.bytes
                        sentBytes += input.bytes.size
                        socket.send(input.bytes.toByteString())
                    }
                    Input.AudioEnd -> {
                        stopped = true
                        stoppedAt = System.nanoTime()
                        finishAudio(scope)
                    }
                    is Input.Drained -> if (input.attempt == attempt) drained = true
                    is Input.Server -> if (input.attempt == attempt) {
                        onPart(input.part, scope)?.let { return it }
                    }
                    is Input.Failed -> if (input.attempt == attempt) fail(input.message, input.retryable, scope)
                }
            }
        }

        /** Handles a part of the current stream; returns the final text on `finish`. */
        private suspend fun onPart(part: GatewayPart, scope: CoroutineScope): String? {
            when (part) {
                is GatewayPart.StreamStart -> part.warnings.forEach { Log.w(TAG, "Stream warning: $it") }
                is GatewayPart.Delta -> {
                    transcript.onDelta(part.delta)
                    show(transcript.text)
                }
                is GatewayPart.Partial -> {
                    transcript.onPartial(part.text, part.startSecond, part.durationInSeconds)
                    show(transcript.text)
                }
                is GatewayPart.Final -> {
                    transcript.onFinal(part.text)
                    shown = transcript.text
                    onPartial(shown)
                }
                is GatewayPart.Finish -> {
                    if (stopped) {
                        Log.d(TAG, "Timing: audio-done->finish ${(System.nanoTime() - stoppedAt) / 1_000_000} ms")
                    } else {
                        Log.w(TAG, "finish before audio-done")
                    }
                    return part.text.ifBlank { transcript.text }
                }
                is GatewayPart.Error -> fail(part.message, retryable = true, scope)
            }
            return null
        }

        /** Live text that only cuts words off the end isn't shown: they come straight back. */
        private fun show(text: String) {
            if (onlyTrims(shown, text)) return
            shown = text
            onPartial(text)
        }

        /**
         * Pads short audio, sends `audio-done` and waits (in [scope]) for the current stream's queue to drain, until
         * [reconnectDeadline] if [afterReconnect], else for [DRAIN_TIMEOUT_MS].
         */
        private fun finishAudio(scope: CoroutineScope, afterReconnect: Boolean = false) {
            // OpenAI's upstream rejects under 100 ms of audio (e.g. an instant stop); pad it with silence.
            val minBytes = rate * 2 / 10
            if (sentBytes < minBytes) socket.send(ByteArray(minBytes - sentBytes).toByteString())
            socket.send(AUDIO_DONE)
            val socket = socket
            val attempt = attempt
            drainJob = scope.launch {
                // On a slow uplink OkHttp may still be sending queued audio; only time the server once it's sent.
                val timeoutMs = if (afterReconnect) reconnectRemainingMs() else DRAIN_TIMEOUT_MS
                withTimeoutOrNull(timeoutMs) { while (socket.queueSize() > 0) delay(DRAIN_POLL_MS) }
                    ?: throw SttException(if (afterReconnect) RECONNECT_TIMED_OUT else SEND_TIMEOUT)
                inputs.send(Input.Drained(attempt))
            }
        }

        /**
         * Reconnects once for a retryable failure and resends the audio; throws [SttException] otherwise. After that,
         * only a handshake that failed without a response (DNS/connect error: nothing was transcribed or billed) is
         * tried again, every [reconnectDelayMs] until [reconnectDeadline], so a brief network drop recovers.
         */
        private suspend fun fail(message: String, retryable: Boolean, scope: CoroutineScope) {
            val handshakeRetry = retried && !listener.opened
            if (!retryable || (retried && !handshakeRetry)) throw SttException(message)
            if (handshakeRetry && reconnectRemainingMs() <= reconnectDelayMs) throw SttException(RECONNECT_TIMED_OUT)
            socket.cancel()
            drainJob?.cancel()
            drained = false
            if (handshakeRetry) {
                Log.w(TAG, "Reconnect failed ($message); trying again in $reconnectDelayMs ms")
                delay(reconnectDelayMs) // Audio keeps queuing in `inputs`.
            } else {
                retried = true
                val budgetMs = reconnectTimeoutMs + sentBytes * 1_000L / RESEND_BYTES_PER_SECOND
                reconnectDeadline = System.nanoTime() + budgetMs * 1_000_000
                val seconds = "%.1f".format(sentBytes / (rate * 2.0))
                Log.w(TAG, "Stream for $model failed ($message); reconnecting once and resending $seconds s")
            }
            attempt++
            // The new stream transcribes the recording from its start. `shown` stays, so only prefix shrinks are
            // hidden (onlyTrims); the panel may jump back once while the new stream catches up.
            transcript = LiveTranscript()
            listener = Listener(attempt, inputs, startedAt)
            socket = connect()
            withTimeoutOrNull(reconnectRemainingMs()) {
                for (chunk in sent) {
                    // OkHttp closes a socket whose send queue exceeds 16 MiB; a long recording is more than that.
                    while (socket.queueSize() > RESEND_QUEUE_LIMIT && !listener.failed) delay(DRAIN_POLL_MS)
                    socket.send(chunk.toByteString())
                }
            } ?: throw SttException(RECONNECT_TIMED_OUT)
            if (stopped) finishAudio(scope, afterReconnect = true)
        }

        private fun reconnectRemainingMs(): Long = (reconnectDeadline - System.nanoTime()) / 1_000_000

        /** OkHttp queues frames until the handshake completes. */
        private fun connect(): WebSocket = httpClient.newWebSocket(request, listener).also { it.send(start) }
    }

    private sealed interface Input {
        class Audio(val bytes: ByteArray) : Input
        data object AudioEnd : Input
        class Drained(val attempt: Int) : Input
        class Server(val attempt: Int, val part: GatewayPart) : Input

        /** [retryable]: false for an HTTP rejection of the handshake (key, model, quota, server). */
        class Failed(val attempt: Int, val message: String, val retryable: Boolean) : Input
    }

    /** Runs on OkHttp's threads; only forwards events to the session. */
    private class Listener(
        private val attempt: Int,
        private val inputs: SendChannel<Input>,
        private val startedAt: Long,
    ) : WebSocketListener() {

        @Volatile
        var failed = false
            private set

        @Volatile
        var opened = false
            private set

        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened = true
            if (attempt == 0) {
                Log.d(TAG, "Timing: transcribe->ws open ${(System.nanoTime() - startedAt) / 1_000_000} ms")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            parseGatewayPart(text)?.let { inputs.trySend(Input.Server(attempt, it)) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            failed = true
            inputs.trySend(Input.Failed(attempt, gatewayClosedMessage(reason), retryable = true))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            failed = true
            val input = if (response != null) {
                // OkHttp closes the rejected upgrade's response only after this returns.
                val body = try {
                    response.body.string()
                } catch (e: IOException) {
                    ""
                }
                Input.Failed(attempt, httpErrorMessage(response.code, body), retryable = false)
            } else {
                Input.Failed(attempt, gatewayNetworkErrorMessage(t), retryable = true)
            }
            inputs.trySend(input)
        }
    }

    private companion object {
        const val TAG = "VercelGatewayStt"
        const val NORMAL_CLOSURE = 1000
        const val STALL_TIMEOUT_MS = 15_000L
        const val STALL_MESSAGE = "Timed out waiting for the transcript"
        const val DRAIN_TIMEOUT_MS = 60_000L
        const val DRAIN_POLL_MS = 10L
        const val SEND_TIMEOUT = "Timed out sending audio to AI Gateway"

        /** A reconnect's base budget (as Handy's), plus [RESEND_BYTES_PER_SECOND] for the audio it resends. */
        const val RECONNECT_TIMEOUT_MS = 10_000L
        const val RESEND_BYTES_PER_SECOND = 200_000L
        const val RECONNECT_DELAY_MS = 1_000L
        const val RECONNECT_TIMED_OUT = "Reconnecting to AI Gateway timed out"

        /** Resent audio waits while more than this is queued, far below OkHttp's 16 MiB limit. */
        const val RESEND_QUEUE_LIMIT = 1L shl 20

        /** Inside OkHttp's 5 min pool keep-alive (the Gateway's HTTP/1.1 idle timeout is unmeasured). */
        const val WARM_UP_WINDOW_MS = 180_000L
    }
}
