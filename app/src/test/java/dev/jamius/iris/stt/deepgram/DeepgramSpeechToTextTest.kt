/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.stt.deepgram

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

class DeepgramSpeechToTextTest {

    private val server = MockWebServer()

    /** Text frames the server got. */
    private val received: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Binary (audio) frames the server got. */
    private val audioFrames: MutableList<ByteString> = Collections.synchronizedList(mutableListOf())

    /** Counted down when the server sees the client close or drop the socket. */
    private val serverSawClose = CountDownLatch(1)

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun stt(
        apiKey: String? = "dg-test",
        httpClient: OkHttpClient = OkHttpClient(),
    ) = DeepgramSpeechToText(
        httpClient,
        apiKey = { apiKey },
        url = server.url("/v1/listen").toString(),
        warmUpUrl = server.url("/v1/listen").toString()
    )

    private fun upgrade(
        onOpen: (WebSocket) -> Unit = {},
        onAudio: (WebSocket) -> Unit = {},
        onCloseStream: (WebSocket) -> Unit = {},
    ) =
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = onOpen(webSocket)

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received += text
                    if (text == CLOSE_STREAM) onCloseStream(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    audioFrames += bytes
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

    @Test(timeout = TEST_TIMEOUT_MS)
    fun streamsBinaryAudioThenCloseStreamAndReturnsTheFinalsOnMetadata() = runBlocking {
        upgrade(onCloseStream = { socket ->
            socket.send(result("Hello there.", isFinal = true))
            socket.send(result("How", isFinal = false))
            socket.send(result("How are you?", isFinal = true))
            socket.send(METADATA)
        })

        val text = stt().transcribe(flowOf(byteArrayOf(1, 2), byteArrayOf(3, 4)), "nova-3", "en", TIMEOUT_MS, {})

        assertEquals("Hello there. How are you?", text)
        val request = server.takeRequest()
        assertEquals("Token dg-test", request.headers["Authorization"])
        assertEquals("nova-3", request.url.queryParameter("model"))
        assertEquals("en", request.url.queryParameter("language"))
        assertEquals("16000", request.url.queryParameter("sample_rate"))
        assertEquals(listOf(listOf<Byte>(1, 2), listOf<Byte>(3, 4)), audioFrames.map { it.toByteArray().toList() })
        assertEquals(listOf(CLOSE_STREAM), received)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun serverCloseAfterCloseStreamReturnsTheFinals() = runBlocking {
        upgrade(onCloseStream = { socket ->
            socket.send(result("Bye.", isFinal = true))
            socket.close(1000, null)
        })

        val text = stt().transcribe(flowOf(byteArrayOf(1, 2)), "nova-3", "", TIMEOUT_MS, {})

        assertEquals("Bye.", text)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun metadataBeforeCloseStreamDoesNotEndTheStream() = runBlocking {
        upgrade(
            onOpen = { it.send(METADATA) },
            onCloseStream = { socket ->
                socket.send(result("Late.", isFinal = true))
                socket.send(METADATA)
            }
        )
        val audio = flow {
            emit(byteArrayOf(1, 2))
            delay(200) // The early Metadata arrives while recording.
        }

        val text = stt().transcribe(audio, "nova-3", "", TIMEOUT_MS, {})

        assertEquals("Late.", text)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun liveTextReachesOnPartial() = runBlocking {
        upgrade(
            onAudio = { socket ->
                socket.send(result("Hel", isFinal = false))
                socket.send(result("Hello.", isFinal = true))
                socket.send(result("How", isFinal = false))
            },
            onCloseStream = { socket ->
                socket.send(result("How are you?", isFinal = true))
                socket.send(METADATA)
            }
        )
        val partials = mutableListOf<String>()
        val interimSeen = CompletableDeferred<Unit>()
        // Holds CloseStream back until the live results have been reported.
        val audio = flow {
            emit(byteArrayOf(1, 2))
            interimSeen.await()
        }

        val text = stt().transcribe(audio, "nova-3", "", TIMEOUT_MS, { live ->
            partials += live
            if (live == "Hello. How") interimSeen.complete(Unit)
        })

        assertEquals("Hello. How are you?", text)
        assertEquals(listOf("Hel", "Hello.", "Hello. How", "Hello. How are you?"), partials)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun errorEventDoesNotFailTheSession() = runBlocking {
        upgrade(
            onOpen = { it.send("""{"type":"Error","variant":"SchemaError","description":"Bad message"}""") },
            onCloseStream = { socket ->
                socket.send(result("Still fine.", isFinal = true))
                socket.send(METADATA)
            }
        )
        val audio = flow {
            emit(byteArrayOf(1, 2))
            delay(200) // The Error arrives while recording.
        }

        val text = stt().transcribe(audio, "nova-3", "", TIMEOUT_MS, {})

        assertEquals("Still fine.", text)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun rejectedHandshakesMapToReadableErrors() {
        server.enqueue(
            MockResponse.Builder().code(401).body("""{"err_code":"INVALID_AUTH","err_msg":"Invalid credentials."}""")
                .build()
        )
        assertEquals(
            "Deepgram rejected the API key. Check it in Iris settings.",
            failure { stt().transcribe(endlessAudio, "nova-3", "", TIMEOUT_MS, {}) }.message
        )
        server.enqueue(MockResponse.Builder().code(402).build())
        assertEquals(
            "Deepgram account is out of credits",
            failure { stt().transcribe(endlessAudio, "nova-3", "", TIMEOUT_MS, {}) }.message
        )
        server.enqueue(
            MockResponse.Builder()
                .code(400)
                .body("""{"err_code":"Bad Request","err_msg":"No such model/language/tier combination found."}""")
                .build()
        )
        assertEquals(
            "Deepgram error: No such model/language/tier combination found.",
            failure { stt().transcribe(endlessAudio, "nova-3", "yo", TIMEOUT_MS, {}) }.message
        )
        server.enqueue(MockResponse.Builder().code(400).addHeader("dg-error", "Bad language").build())
        assertEquals(
            "Deepgram error: Bad language",
            failure { stt().transcribe(endlessAudio, "nova-3", "yo", TIMEOUT_MS, {}) }.message
        )
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun serverCloseMidSessionFailsTheSession() {
        upgrade(onAudio = { it.close(1011, "NET-0001") })

        val error = failure {
            stt().transcribe(
                flow {
                    emit(byteArrayOf(1, 2))
                    awaitCancellation()
                },
                "nova-3",
                "",
                TIMEOUT_MS,
                {}
            )
        }

        assertEquals("Deepgram closed the connection: NET-0001", error.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun timesOutWaitingForMetadata() {
        upgrade()

        val error = failure { stt().transcribe(flowOf(byteArrayOf(1, 2)), "nova-3", "", 200, {}) }

        assertEquals("Timed out waiting for the transcript", error.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun cancellingClosesTheSocket() = runBlocking {
        upgrade()
        val audio = flow {
            emit(byteArrayOf(1, 2))
            awaitCancellation()
        }
        val session = launch { stt().transcribe(audio, "nova-3", "", TIMEOUT_MS, {}) }
        while (audioFrames.isEmpty()) delay(10) // Connected: the server got the audio.

        session.cancelAndJoin()

        assertTrue(serverSawClose.await(5, TimeUnit.SECONDS))
        assertTrue(received.isEmpty()) // No CloseStream.
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun audioFailureIsRethrownWithoutCloseStream() {
        upgrade()
        // The controller's audio flow ends like this when VAD let nothing through.
        val audio = flow<ByteArray> {
            emit(byteArrayOf(1, 2))
            while (audioFrames.isEmpty()) delay(10)
            throw SttException("No speech detected")
        }

        val error = failure { stt().transcribe(audio, "nova-3", "", TIMEOUT_MS, {}) }

        assertEquals("No speech detected", error.message)
        assertTrue(serverSawClose.await(5, TimeUnit.SECONDS))
        assertTrue(received.isEmpty())
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun missingApiKeyFailsWithoutConnecting() {
        var collected = false
        val audio = flow<ByteArray> { collected = true }

        val error = failure { stt(apiKey = null).transcribe(audio, "nova-3", "", TIMEOUT_MS, {}) }

        assertEquals("Add your Deepgram API key in Iris settings", error.message)
        assertFalse(collected)
        assertEquals(0, server.requestCount)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun emptyChunksAreNotSent() = runBlocking {
        upgrade(onCloseStream = { it.send(METADATA) })

        val text = stt().transcribe(flowOf(ByteArray(0), byteArrayOf(1, 2)), "nova-3", "", TIMEOUT_MS, {})

        assertEquals("", text) // Blank: the controller shows "No speech detected".
        assertEquals(listOf(2), audioFrames.map { it.size })
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun streamsAt16kHz() {
        val stt = stt()
        assertTrue(stt.streams)
        assertTrue(stt.liveText("nova-3"))
        assertEquals(16_000, stt.sampleRate("nova-3"))
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun webSocketReusesTheWarmedUpConnection() = runBlocking {
        server.enqueue(MockResponse.Builder().code(405).build())
        upgrade(onCloseStream = { it.send(METADATA) })
        val httpClient = OkHttpClient()
        val stt = stt(httpClient = httpClient)

        stt.warmUp()
        val warmUp = server.takeRequest()
        while (httpClient.connectionPool.idleConnectionCount() == 0) Thread.sleep(10) // Response consumed.
        stt.transcribe(flowOf(byteArrayOf(1, 2)), "nova-3", "", TIMEOUT_MS, {})

        assertEquals("HEAD", warmUp.method)
        assertEquals("/v1/listen", warmUp.target)
        assertNull(warmUp.headers["Authorization"])
        val handshake = server.takeRequest()
        assertEquals("websocket", handshake.headers["Upgrade"])
        assertEquals(warmUp.connectionIndex, handshake.connectionIndex)
        assertEquals(1, handshake.exchangeIndex) // Second exchange on that connection.
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun warmUpIsSkippedUntilASessionTakesTheWarmedConnection() = runBlocking {
        server.enqueue(MockResponse.Builder().code(405).build())
        upgrade(onCloseStream = { it.send(METADATA) })
        server.enqueue(MockResponse.Builder().code(405).build())
        val httpClient = OkHttpClient()
        val stt = stt(httpClient = httpClient)

        stt.warmUp()
        stt.warmUp() // Within the window: skipped.
        while (httpClient.connectionPool.idleConnectionCount() == 0) Thread.sleep(10)
        // The call may still be finishing after its connection is pooled; a skipped one would add a request.
        while (httpClient.dispatcher.runningCallsCount() > 0) Thread.sleep(10)
        assertEquals(1, server.requestCount)

        stt.transcribe(flowOf(byteArrayOf(1, 2)), "nova-3", "", TIMEOUT_MS, {})
        stt.warmUp() // The session took the warmed connection: warms a new one.

        assertEquals("HEAD", server.takeRequest().method)
        assertEquals("websocket", server.takeRequest().headers["Upgrade"])
        assertEquals("HEAD", server.takeRequest().method)
    }

    private fun result(transcript: String, isFinal: Boolean) =
        """{"type":"Results","channel_index":[0,1],"duration":1.0,"start":0.0,"is_final":$isFinal,
           "speech_final":false,"channel":{"alternatives":[{"transcript":"$transcript","confidence":0.9}]}}"""

    private companion object {
        const val TEST_TIMEOUT_MS = 15_000L
        const val TIMEOUT_MS = 10_000L
        const val METADATA = """{"type":"Metadata","request_id":"r","duration":0.1,"channels":1}"""
    }
}
