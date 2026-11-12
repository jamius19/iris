/*
* Author: Jamius Siam
* Since: 12/11/2026
*/

package dev.jamius.iris.stt.vercel

import dev.jamius.iris.stt.SttException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.QueueDispatcher
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VercelGatewaySpeechToTextTest {

    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun stt(
        apiKey: String? = "vck-test",
        reconnectTimeoutMs: Long = 5_000,
        httpClient: OkHttpClient = OkHttpClient(),
    ) = VercelGatewaySpeechToText(
        httpClient,
        apiKey = { apiKey },
        url = server.url("/v4/ai/transcription-model").toString(),
        warmUpUrl = server.url("/v1/models").toString(),
        reconnectTimeoutMs = reconnectTimeoutMs,
        reconnectDelayMs = 50
    )

    /** The server side of one WebSocket: records the frames it gets (text as String, binary as ByteString). */
    private class ServerStream(
        private val onText: (WebSocket, String) -> Unit = { _, _ -> },
        private val onBinary: (WebSocket) -> Unit = {},
    ) : WebSocketListener() {
        val frames: MutableList<Any> = Collections.synchronizedList(mutableListOf())

        /** Counted down when the client closes or drops the socket. */
        val sawClose = CountDownLatch(1)

        override fun onMessage(webSocket: WebSocket, text: String) {
            frames += text
            onText(webSocket, text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            frames += bytes
            onBinary(webSocket)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null) // Reply like a real server, so both sides finish closing.
            sawClose.countDown()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = sawClose.countDown()

        /** Frame types: text frames by their JSON `type`, binary frames as is. */
        fun types(): List<Any> = synchronized(frames) { frames.map { if (it is String) type(it) else it } }
    }

    private fun upgrade(stream: ServerStream): ServerStream {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(stream).build())
        return stream
    }

    /** Answers `audio-done` with [parts]. */
    private fun answering(vararg parts: String) = ServerStream(onText = { socket, text ->
        if (type(text) == AUDIO_DONE_TYPE) parts.forEach(socket::send)
    })

    /** The connection is closed before the handshake gets a response. */
    private val noResponse = MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build()

    private val endlessAudio: Flow<ByteArray> = flow { awaitCancellation() }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun streamsAudioAndReturnsTheFinishText() = runBlocking {
        val stream = upgrade(answering(delta("Hello"), delta(" world"), finish("Hello, world.")))
        val partials = mutableListOf<String>()

        val text = stt().transcribe(flowOf(CHUNK_A, CHUNK_B), MODEL, "en", TIMEOUT_MS, partials::add)

        assertEquals("Hello, world.", text)
        assertEquals(listOf("Hello", "Hello world"), partials)
        val request = server.takeRequest()
        assertEquals("/v4/ai/transcription-model?ai-model-id=openai%2Fgpt-live-transcribe", request.target)
        assertEquals("Bearer vck-test", request.headers["Authorization"])
        assertEquals("ai-gateway-transcription.v1", request.headers["Sec-WebSocket-Protocol"])
        assertEquals("0.0.1", request.headers["ai-gateway-protocol-version"])
        assertEquals("api-key", request.headers["ai-gateway-auth-method"])
        assertEquals("4", request.headers["ai-transcription-model-specification-version"])
        assertEquals(MODEL, request.headers["ai-model-id"])
        assertEquals(
            Json.parseToJsonElement(
                """
                {"type":"transcription-stream.start","inputAudioFormat":{"type":"audio/pcm","rate":24000},
                 "providerOptions":{"openai":{"language":"en"}}}
                """
            ),
            Json.parseToJsonElement(stream.frames[0] as String)
        )
        assertEquals(
            listOf(START_TYPE, CHUNK_A.toByteString(), CHUNK_B.toByteString(), AUDIO_DONE_TYPE),
            stream.types()
        )
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun shortAudioIsPaddedWithSilenceToOneHundredMsAtTheModelsRate() = runBlocking {
        val stream = upgrade(answering(finish("")))

        stt().transcribe(flowOf(byteArrayOf(1, 2)), "google/gemini-3.5-transcribe-live", "", TIMEOUT_MS, {})

        val audio = stream.frames.filterIsInstance<ByteString>()
        assertEquals(listOf(2, 16_000 * 2 / 10 - 2), audio.map { it.size })
        assertTrue(audio[1].toByteArray().all { it == 0.toByte() })
        val format = Json.parseToJsonElement(stream.frames[0] as String).jsonObject["inputAudioFormat"]!!.jsonObject
        val rate = format["rate"]!!.jsonPrimitive.content.toInt()
        assertEquals(16_000, rate)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun assemblesLiveTextAndFallsBackToItForABlankFinish() = runBlocking {
        upgrade(
            answering(
                """{"type":"stream-start","warnings":[{"type":"unsupported","feature":"language"}]}""",
                partial("one two three"),
                partial("one two"), // Only cuts words off: not shown.
                partial("one two three four"),
                """{"type":"response-metadata","modelId":"x"}""",
                final("One two three four."),
                finish(" ")
            )
        )
        val partials = mutableListOf<String>()

        val text = stt().transcribe(flowOf(CHUNK_A, CHUNK_B), MODEL, "", TIMEOUT_MS, partials::add)

        assertEquals("One two three four.", text)
        assertEquals(listOf("one two three", "one two three four", "One two three four."), partials)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun errorPartWhileListeningReconnectsAndResendsTheAudio() = runBlocking {
        val reconnected = CompletableDeferred<Unit>()
        upgrade(ServerStream(onBinary = { it.send(ERROR_PART) }))
        val second = upgrade(ServerStream(onText = { socket, text ->
            when (type(text)) {
                START_TYPE -> reconnected.complete(Unit)
                AUDIO_DONE_TYPE -> socket.send(finish("Hi."))
            }
        }))
        val audio = flow {
            emit(CHUNK_A)
            reconnected.await()
            emit(CHUNK_B)
        }

        val text = stt().transcribe(audio, MODEL, "", TIMEOUT_MS, {})

        assertEquals("Hi.", text)
        assertEquals(2, server.requestCount)
        assertEquals(
            listOf(START_TYPE, CHUNK_A.toByteString(), CHUNK_B.toByteString(), AUDIO_DONE_TYPE),
            second.types()
        )
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun closeWithoutFinishAfterStopReconnectsAndResendsEverything() = runBlocking {
        upgrade(ServerStream(onText = { socket, text ->
            if (type(text) == AUDIO_DONE_TYPE) socket.close(1011, "Upstream gone")
        }))
        val second = upgrade(answering(finish("Hi.")))

        val text = stt().transcribe(flowOf(CHUNK_A, CHUNK_B), MODEL, "", TIMEOUT_MS, {})

        assertEquals("Hi.", text)
        assertEquals(
            listOf(START_TYPE, CHUNK_A.toByteString(), CHUNK_B.toByteString(), AUDIO_DONE_TYPE),
            second.types()
        )
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun secondFailureFailsTheSession() {
        upgrade(answering(ERROR_PART))
        upgrade(answering(ERROR_PART))

        val error = assertThrows(SttException::class.java) {
            runBlocking { stt().transcribe(flowOf(CHUNK_A), MODEL, "", TIMEOUT_MS, {}) }
        }
        assertEquals("AI Gateway error: Boom", error.message)
        assertEquals(2, server.requestCount)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun reconnectHandshakeWithoutAResponseIsTriedAgain() = runBlocking {
        upgrade(ServerStream(onBinary = { it.cancel() })) // Drops after opening: no HTTP response.
        server.enqueue(noResponse)
        val third = upgrade(answering(finish("Hi.")))

        val text = stt().transcribe(flowOf(CHUNK_A, CHUNK_B), MODEL, "", TIMEOUT_MS, {})

        assertEquals("Hi.", text)
        assertEquals(3, server.requestCount)
        assertEquals(
            listOf(START_TYPE, CHUNK_A.toByteString(), CHUNK_B.toByteString(), AUDIO_DONE_TYPE),
            third.types()
        )
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun handshakesThatKeepFailingWithoutAResponseEndAtTheReconnectDeadline() {
        (server.dispatcher as QueueDispatcher).setFailFast(noResponse)
        val startedAt = System.nanoTime()

        val error = assertThrows(SttException::class.java) {
            runBlocking { stt(reconnectTimeoutMs = 500).transcribe(endlessAudio, MODEL, "", TIMEOUT_MS, {}) }
        }
        assertEquals("Reconnecting to AI Gateway timed out", error.message)
        assertTrue(server.requestCount >= 3) // The first try, the reconnect and at least one more handshake.
        assertTrue((System.nanoTime() - startedAt) / 1_000_000 < 3_000)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun stallAfterTheAudioReconnectsAndResendsEverything() = runBlocking {
        upgrade(ServerStream())
        val second = upgrade(answering(finish("Hi.")))

        val text = stt().transcribe(flowOf(CHUNK_A, CHUNK_B), MODEL, "", 200, {})

        assertEquals("Hi.", text)
        assertEquals(
            listOf(START_TYPE, CHUNK_A.toByteString(), CHUNK_B.toByteString(), AUDIO_DONE_TYPE),
            second.types()
        )
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun rejectedKeyIsNotRetried() {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"message":"Invalid key"}}""").build())

        val error = assertThrows(SttException::class.java) {
            runBlocking { stt().transcribe(endlessAudio, MODEL, "", TIMEOUT_MS, {}) }
        }
        assertEquals("AI Gateway rejected the API key. Check it in Iris settings.", error.message)
        assertEquals(1, server.requestCount)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun unknownModelShowsTheGatewayMessageAndIsNotRetried() {
        server.enqueue(
            MockResponse.Builder().code(404).body("""{"error":{"message":"Model 'x/y' not found"}}""").build()
        )

        val error = assertThrows(SttException::class.java) {
            runBlocking { stt().transcribe(endlessAudio, "x/y", "", TIMEOUT_MS, {}) }
        }
        assertEquals("AI Gateway error: Model 'x/y' not found", error.message)
        assertEquals(1, server.requestCount)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun timesOutWhenTheServerStallsAgainAfterTheReconnect() {
        upgrade(ServerStream())
        upgrade(ServerStream())

        val error = assertThrows(SttException::class.java) {
            runBlocking { stt().transcribe(flowOf(CHUNK_A), MODEL, "", 200, {}) }
        }
        assertEquals("Timed out waiting for the transcript", error.message)
        assertEquals(2, server.requestCount)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun cancellingClosesTheSocket() = runBlocking {
        val stream = upgrade(ServerStream())
        val session = launch { stt().transcribe(endlessAudio, MODEL, "", TIMEOUT_MS, {}) }
        while (stream.frames.isEmpty()) delay(10) // Connected: the server got the start frame.

        session.cancelAndJoin()

        assertTrue(stream.sawClose.await(5, TimeUnit.SECONDS))
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun audioFailureIsRethrownWithoutPaddingOrAudioDone() {
        val stream = upgrade(ServerStream())
        // The controller's audio flow ends like this when VAD let nothing through.
        val audio = flow<ByteArray> {
            emit(CHUNK_A) // Under 100 ms: a normal end would pad it.
            while (stream.frames.size < 2) delay(10) // The server got the start frame and the chunk.
            throw SttException("No speech detected")
        }

        val error = assertThrows(SttException::class.java) {
            runBlocking { stt().transcribe(audio, MODEL, "", TIMEOUT_MS, {}) }
        }

        assertEquals("No speech detected", error.message)
        assertTrue(stream.sawClose.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(START_TYPE, CHUNK_A.toByteString()), stream.types())
        assertEquals(1, server.requestCount)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun missingApiKeyFailsWithoutConnecting() {
        val error = assertThrows(SttException::class.java) {
            runBlocking { stt(apiKey = " ").transcribe(endlessAudio, MODEL, "", TIMEOUT_MS, {}) }
        }
        assertEquals("Add your Vercel AI Gateway API key in Iris settings", error.message)
        assertEquals(0, server.requestCount)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun modelIdThatCantBeAHeaderFailsWithoutConnecting() {
        val error = assertThrows(SttException::class.java) {
            runBlocking { stt().transcribe(endlessAudio, "openai/gpt-ü", "", TIMEOUT_MS, {}) }
        }
        assertEquals("Invalid model id: openai/gpt-ü", error.message)
        assertEquals(0, server.requestCount)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun webSocketReusesTheWarmedUpConnection() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).build())
        upgrade(answering(finish("Hi.")))
        val httpClient = OkHttpClient()
        val stt = stt(httpClient = httpClient)

        stt.warmUp()
        val warmUp = server.takeRequest()
        while (httpClient.connectionPool.idleConnectionCount() == 0) Thread.sleep(10) // Response consumed.
        stt.transcribe(flowOf(CHUNK_A), MODEL, "", TIMEOUT_MS, {})

        assertEquals("HEAD", warmUp.method)
        assertEquals("/v1/models", warmUp.target)
        assertNull(warmUp.headers["Authorization"])
        val handshake = server.takeRequest()
        assertEquals("websocket", handshake.headers["Upgrade"])
        assertEquals(warmUp.connectionIndex, handshake.connectionIndex)
        assertEquals(1, handshake.exchangeIndex) // Second exchange on that connection.
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun warmUpIsSkippedUntilASessionTakesTheWarmedConnection() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).build())
        upgrade(answering(finish("Hi.")))
        server.enqueue(MockResponse.Builder().code(200).build())
        val httpClient = OkHttpClient()
        val stt = stt(httpClient = httpClient)

        stt.warmUp()
        stt.warmUp() // Within the window: skipped.
        while (httpClient.connectionPool.idleConnectionCount() == 0) Thread.sleep(10)
        // The call may still be finishing after its connection is pooled; a skipped one would add a request.
        while (httpClient.dispatcher.runningCallsCount() > 0) Thread.sleep(10)
        assertEquals(1, server.requestCount)

        stt.transcribe(flowOf(CHUNK_A), MODEL, "", TIMEOUT_MS, {})
        stt.warmUp() // The session took the warmed connection: warms a new one.

        assertEquals("HEAD", server.takeRequest().method)
        assertEquals("websocket", server.takeRequest().headers["Upgrade"])
        assertEquals("HEAD", server.takeRequest().method)
    }

    private fun delta(text: String) = """{"type":"transcript-delta","delta":"$text"}"""

    private fun partial(text: String) = """{"type":"transcript-partial","text":"$text"}"""

    private fun final(text: String) = """{"type":"transcript-final","text":"$text"}"""

    private fun finish(text: String) = """{"type":"finish","text":"$text","segments":[]}"""

    private companion object {
        const val TEST_TIMEOUT_MS = 15_000L
        const val TIMEOUT_MS = 10_000L
        const val MODEL = "openai/gpt-live-transcribe"
        const val START_TYPE = "transcription-stream.start"
        const val AUDIO_DONE_TYPE = "transcription-stream.audio-done"
        const val ERROR_PART = """{"type":"error","error":{"name":"Error","message":"Boom"}}"""

        /** 50 ms each at 24 kHz, so two of them need no padding. */
        val CHUNK_A = ByteArray(2_400) { 1 }
        val CHUNK_B = ByteArray(2_400) { 2 }

        fun type(message: String) = Json.parseToJsonElement(message).jsonObject["type"]!!.jsonPrimitive.content
    }
}
