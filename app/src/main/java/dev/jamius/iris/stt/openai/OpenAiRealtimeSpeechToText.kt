/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.stt.openai

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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * OpenAI Realtime transcription-only session over a WebSocket. Audio is appended as it is recorded; when the
 * audio flow completes the buffer is committed and the session waits for that item's final transcript.
 */
class OpenAiRealtimeSpeechToText(
    private val httpClient: OkHttpClient,
    private val apiKey: suspend () -> String?,
    private val url: String = REALTIME_URL,
    private val finalTimeoutMs: Long = FINAL_TIMEOUT_MS,
) : SpeechToTextProvider {

    override suspend fun transcribe(
        audio: Flow<ByteArray>,
        model: String,
        language: String,
        onPartial: (String) -> Unit,
    ): String {
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: throw SttException("Add your OpenAI API key in Iris settings")
        val events = Channel<RealtimeEvent>(Channel.UNLIMITED)
        val request = Request.Builder().url(url).header("Authorization", "Bearer $key").build()
        val socket = httpClient.newWebSocket(request, Listener(events))
        var closed = false
        try {
            val text = coroutineScope {
                val final = async { awaitFinal(events, onPartial) }
                // OkHttp queues messages until the handshake completes.
                socket.send(sessionUpdate(model, language))
                audio.collect { socket.send(appendAudio(it)) }
                socket.send(COMMIT_AUDIO)
                // On a slow uplink OkHttp may still be sending queued audio; only time the server once it's sent.
                // A socket failure meanwhile fails `final`, which cancels this wait.
                withTimeoutOrNull(DRAIN_TIMEOUT_MS) { while (socket.queueSize() > 0) delay(DRAIN_POLL_MS) }
                    ?: throw SttException("Timed out sending audio to OpenAI")
                withTimeoutOrNull(finalTimeoutMs) { final.await() }
                    ?: throw SttException("Timed out waiting for the transcript")
            }
            socket.close(NORMAL_CLOSURE, null)
            closed = true
            return text
        } finally {
            // Error or cancellation: drop queued audio and tear the socket down immediately.
            if (!closed) socket.cancel()
        }
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
    private class Listener(private val events: SendChannel<RealtimeEvent>) : WebSocketListener() {

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
        const val NORMAL_CLOSURE = 1000
        const val FINAL_TIMEOUT_MS = 10_000L
        const val DRAIN_TIMEOUT_MS = 60_000L
        const val DRAIN_POLL_MS = 100L
    }
}
