/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.stt.soniox

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
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SonioxSpeechToTextTest {

    private val server = MockWebServer()

    /** Every frame the server got, in order: text frames as is, binary ones as "bin:<bytes>". */
    private val frames: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Counted down when the server sees the client close or drop the socket. */
    private val serverSawClose = CountDownLatch(1)

    /** Models [SonioxSpeechToText] looked up. */
    private val lookups: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun stt(
        apiKey: String? = "sx-test",
        httpClient: OkHttpClient = OkHttpClient(),
        modelInfo: suspend (String) -> SonioxModel? = { null },
    ) = SonioxSpeechToText(
        httpClient,
        apiKey = { apiKey },
        modelInfo = {
            lookups += it
            modelInfo(it)
        },
        url = server.url("/transcribe-websocket").toString()
    )

    private fun upgrade(
        onOpen: (WebSocket) -> Unit = {},
        onAudio: (WebSocket) -> Unit = {},
        onEnd: (WebSocket) -> Unit = {},
    ) =
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = onOpen(webSocket)

                override fun onMessage(webSocket: WebSocket, text: String) {
                    frames += text
                    if (text == END_OF_AUDIO) onEnd(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    frames += "bin:" + bytes.toByteArray().joinToString(",")
                    onAudio(webSocket)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null) // Reply like a real server, so both sides finish closing.
                    serverSawClose.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    serverSawClose.countDown()
            }).build()
        )

    private val endlessAudio: Flow<ByteArray> = flow { awaitCancellation() }

    private fun failure(block: suspend () -> Unit): SttException =
        assertThrows(SttException::class.java) { runBlocking { block() } }

    private fun start(model: String = "stt-rt-v6", hints: List<String> = emptyList(), strict: Boolean = false) =
        Json.parseToJsonElement(sonioxStartMessage(model, hints, strict))

    @Test(timeout = TEST_TIMEOUT_MS)
    fun sendsStartThenAudioThenEndAndReturnsTheFinalsWhenFinished() = runBlocking {
        upgrade(onEnd = { socket ->
            socket.send(tokens(final("Hello"), final(" there."), partial(" How")))
            socket.send(tokens(final(" How are you?")))
            socket.send(FINISHED)
            socket.close(1000, null)
        })

        val text = stt().transcribe(flowOf(byteArrayOf(1, 2), byteArrayOf(3, 4)), "stt-rt-v6", "", TIMEOUT_MS, {})

        assertEquals("Hello there. How are you?", text)
        val request = server.takeRequest()
        assertEquals("Bearer sx-test", request.headers["Authorization"])
        assertEquals("/transcribe-websocket", request.target)
        assertNull(request.headers["Sec-WebSocket-Protocol"])
        assertEquals(start(), Json.parseToJsonElement(frames[0]))
        assertEquals(listOf("bin:1,2", "bin:3,4", ""), frames.drop(1))
        assertTrue(lookups.isEmpty()) // No hints: no catalogue lookup.
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun hintsAreSentStrictWhenTheModelSupportsIt() = runBlocking {
        upgrade(onEnd = { it.send(FINISHED) })

        stt(modelInfo = { SonioxModel(it, "real_time", true) })
            .transcribe(flowOf(byteArrayOf(1)), "stt-rt-v6", "en, BN", TIMEOUT_MS, {})

        assertEquals(start(hints = listOf("en", "bn"), strict = true), Json.parseToJsonElement(frames[0]))
        assertEquals(listOf("stt-rt-v6"), lookups)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun strictIsOmittedWhenUnsupportedOrUnknown() = runBlocking {
        for (info in listOf<SonioxModel?>(null, SonioxModel("stt-rt-v6", "real_time", false))) {
            frames.clear()
            upgrade(onEnd = { it.send(FINISHED) })

            stt(modelInfo = { info }).transcribe(flowOf(byteArrayOf(1)), "stt-rt-v6", "en", TIMEOUT_MS, {})

            assertEquals(start(hints = listOf("en")), Json.parseToJsonElement(frames[0]))
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun slowModelLookupKeepsStartFirstAndChunksInOrder() = runBlocking {
        upgrade(onEnd = { it.send(FINISHED) })
        val stt = stt(modelInfo = {
            delay(200)
            SonioxModel(it, "real_time", true)
        })

        stt.transcribe(flowOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)), "stt-rt-v6", "en", TIMEOUT_MS, {})

        assertEquals(start(hints = listOf("en"), strict = true), Json.parseToJsonElement(frames[0]))
        assertEquals(listOf("bin:1", "bin:2", "bin:3", ""), frames.drop(1))
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun liveTextIsFinalsPlusTheReplacedTail() = runBlocking {
        upgrade(
            onAudio = { socket ->
                socket.send(tokens(partial("Hel")))
                socket.send(tokens(final("Hello"), partial(" wor")))
                socket.send(tokens(partial(" world")))
            },
            onEnd = { socket ->
                socket.send(tokens(final(" world.")))
                socket.send(FINISHED)
            }
        )
        val partials = mutableListOf<String>()
        val tailSeen = CompletableDeferred<Unit>()
        // Holds the end back until the live responses have been reported.
        val audio = flow {
            emit(byteArrayOf(1, 2))
            tailSeen.await()
        }

        val text = stt().transcribe(audio, "stt-rt-v6", "", TIMEOUT_MS, { live ->
            partials += live
            if (live == "Hello world") tailSeen.complete(Unit)
        })

        assertEquals("Hello world.", text)
        assertEquals(listOf("Hel", "Hello wor", "Hello world", "Hello world.", "Hello world."), partials)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun errorFrameFailsWithItsMessage() {
        upgrade(onOpen = { socket ->
            socket.send("""{"tokens":[],"error_code":401,"error_type":"unauthenticated","error_message":"Bad key."}""")
            socket.close(1000, null)
        })

        val error = failure { stt().transcribe(endlessAudio, "stt-rt-v6", "", TIMEOUT_MS, {}) }

        assertEquals("Soniox rejected the API key. Check it in Iris settings.", error.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun noAudioReceivedIsBlank() = runBlocking {
        upgrade(onEnd = { socket ->
            socket.send(
                """{"tokens":[],"error_code":400,"error_type":"invalid_request","error_message":"No audio received."}"""
            )
            socket.close(1000, null)
        })

        // Blank: the controller shows "No speech detected".
        assertEquals("", stt().transcribe(flowOf(), "stt-rt-v6", "", TIMEOUT_MS, {}))
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun closeBeforeFinishedFails() {
        upgrade(onEnd = { socket ->
            socket.send(tokens(final("Half")))
            socket.close(1001, "Going away")
        })

        val error = failure { stt().transcribe(flowOf(byteArrayOf(1)), "stt-rt-v6", "", TIMEOUT_MS, {}) }

        assertEquals("Soniox closed the connection: Going away", error.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun rejectedHandshakeMaps() {
        server.enqueue(
            MockResponse.Builder().code(503).body("""{"status_code":503,"message":"Overloaded"}""").build()
        )

        val error = failure { stt().transcribe(endlessAudio, "stt-rt-v6", "", TIMEOUT_MS, {}) }

        assertEquals("Soniox server error (503)", error.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun timesOutWaitingForFinished() {
        upgrade()

        val error = failure { stt().transcribe(flowOf(byteArrayOf(1, 2)), "stt-rt-v6", "", 200, {}) }

        assertEquals("Timed out waiting for the transcript", error.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun cancellingClosesTheSocket() = runBlocking {
        upgrade()
        val audio = flow {
            emit(byteArrayOf(1, 2))
            awaitCancellation()
        }
        val session = launch { stt().transcribe(audio, "stt-rt-v6", "", TIMEOUT_MS, {}) }
        while (frames.size < 2) delay(10) // Connected: the server got the start and the audio.

        session.cancelAndJoin()

        assertTrue(serverSawClose.await(5, TimeUnit.SECONDS))
        assertFalse(END_OF_AUDIO in frames)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun audioFailureIsRethrownWithoutTheEnd() {
        upgrade()
        // The controller's audio flow ends like this when VAD let nothing through.
        val audio = flow<ByteArray> {
            emit(byteArrayOf(1, 2))
            while (frames.size < 2) delay(10)
            throw SttException("No speech detected")
        }

        val error = failure { stt().transcribe(audio, "stt-rt-v6", "", TIMEOUT_MS, {}) }

        assertEquals("No speech detected", error.message)
        assertTrue(serverSawClose.await(5, TimeUnit.SECONDS))
        assertFalse(END_OF_AUDIO in frames)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun missingApiKeyFailsWithoutConnecting() {
        var collected = false
        val audio = flow<ByteArray> { collected = true }

        val error = failure { stt(apiKey = null).transcribe(audio, "stt-rt-v6", "", TIMEOUT_MS, {}) }

        assertEquals("Add your Soniox API key in Iris settings", error.message)
        assertFalse(collected)
        assertEquals(0, server.requestCount)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun emptyChunksAreNotSent() = runBlocking {
        upgrade(onEnd = { it.send(FINISHED) })

        stt().transcribe(flowOf(ByteArray(0), byteArrayOf(1, 2), ByteArray(0)), "stt-rt-v6", "", TIMEOUT_MS, {})

        assertEquals(listOf("bin:1,2", ""), frames.drop(1))
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun emptyAudioStillSendsStartThenEnd() = runBlocking {
        upgrade(onEnd = { it.send(FINISHED) })

        val text = stt().transcribe(flowOf(), "stt-rt-v6", "", TIMEOUT_MS, {})

        assertEquals("", text)
        assertEquals(start(), Json.parseToJsonElement(frames[0]))
        assertEquals(listOf(""), frames.drop(1))
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun streamsAt16kHzAsRecorded() {
        val stt = stt()
        assertTrue(stt.streams)
        assertTrue(stt.liveText("stt-rt-v6"))
        assertFalse(stt.allowsSpeedUp)
        assertEquals(16_000, stt.sampleRate("stt-rt-v6"))
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun webSocketReusesTheWarmedUpConnection() = runBlocking {
        server.enqueue(MockResponse.Builder().code(426).build())
        upgrade(onEnd = { it.send(FINISHED) })
        val httpClient = OkHttpClient()
        val stt = stt(httpClient = httpClient)

        stt.warmUp()
        val warmUp = server.takeRequest()
        while (httpClient.connectionPool.idleConnectionCount() == 0) Thread.sleep(10) // Response consumed.
        stt.transcribe(flowOf(byteArrayOf(1, 2)), "stt-rt-v6", "", TIMEOUT_MS, {})

        assertEquals("HEAD", warmUp.method)
        assertEquals("/transcribe-websocket", warmUp.target)
        assertNull(warmUp.headers["Authorization"])
        val handshake = server.takeRequest()
        assertEquals("websocket", handshake.headers["Upgrade"])
        assertEquals(warmUp.connectionIndex, handshake.connectionIndex)
        assertEquals(1, handshake.exchangeIndex) // Second exchange on that connection.
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun warmUpIsSkippedUntilASessionTakesTheWarmedConnection() = runBlocking {
        server.enqueue(MockResponse.Builder().code(426).build())
        upgrade(onEnd = { it.send(FINISHED) })
        server.enqueue(MockResponse.Builder().code(426).build())
        val httpClient = OkHttpClient()
        val stt = stt(httpClient = httpClient)

        stt.warmUp()
        stt.warmUp() // Within the window: skipped.
        while (httpClient.connectionPool.idleConnectionCount() == 0) Thread.sleep(10)
        // The call may still be finishing after its connection is pooled; a skipped one would add a request.
        while (httpClient.dispatcher.runningCallsCount() > 0) Thread.sleep(10)
        assertEquals(1, server.requestCount)

        stt.transcribe(flowOf(byteArrayOf(1, 2)), "stt-rt-v6", "", TIMEOUT_MS, {})
        stt.warmUp() // The session took the warmed connection: warms a new one.

        assertEquals("HEAD", server.takeRequest().method)
        assertEquals("websocket", server.takeRequest().headers["Upgrade"])
        assertEquals("HEAD", server.takeRequest().method)
    }

    private fun final(text: String) = """{"text":"$text","start_ms":0,"end_ms":10,"confidence":0.9,"is_final":true}"""

    private fun partial(text: String) = """{"text":"$text","confidence":0.5,"is_final":false}"""

    private fun tokens(vararg tokens: String) =
        tokens.joinToString(",", """{"tokens":[""", """],"final_audio_proc_ms":0,"total_audio_proc_ms":0}""")

    private companion object {
        const val TEST_TIMEOUT_MS = 15_000L
        const val TIMEOUT_MS = 10_000L
        const val FINISHED = """{"tokens":[],"final_audio_proc_ms":1560,"total_audio_proc_ms":1680,"finished":true}"""
    }
}
