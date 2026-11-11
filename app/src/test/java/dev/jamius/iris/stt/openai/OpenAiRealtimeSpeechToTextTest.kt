/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.stt.openai

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
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OpenAiRealtimeSpeechToTextTest {

    private val server = MockWebServer()
    private val received: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    /** Counted down when the server sees the client close or drop the socket. */
    private val serverSawClose = CountDownLatch(1)

    private fun stt(
        apiKey: String? = "sk-test",
        finalTimeoutMs: Long = 10_000,
        httpClient: OkHttpClient = OkHttpClient(),
    ) = OpenAiRealtimeSpeechToText(
        httpClient,
        apiKey = { apiKey },
        url = server.url("/v1/realtime?intent=transcription").toString(),
        warmUpUrl = server.url("/v1/models").toString(),
        finalTimeoutMs = finalTimeoutMs
    )

    private fun upgrade(
        onOpen: (WebSocket) -> Unit = {},
        closeReplyDelayMs: Long = 0,
        onMessage: (WebSocket, String) -> Unit = { _, _ -> },
    ) =
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = onOpen(webSocket)

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received += text
                    onMessage(webSocket, text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Thread.sleep(closeReplyDelayMs)
                    webSocket.close(code, null) // Reply like a real server, so both sides finish closing.
                    serverSawClose.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    serverSawClose.countDown()
            }).build()
        )

    private val endlessAudio: Flow<ByteArray> = flow { awaitCancellation() }

    private fun type(message: String) = Json.parseToJsonElement(message).jsonObject["type"]!!.jsonPrimitive.content

    @Test(timeout = TEST_TIMEOUT_MS)
    fun streamsAudioCommitsAndReturnsFinalTranscript() = runBlocking {
        upgrade(onMessage = { socket, text ->
            if (type(text) == "input_audio_buffer.commit") {
                socket.send("""{"type":"input_audio_buffer.committed","item_id":"item_1"}""")
                socket.send(delta("Hello"))
                socket.send(delta(" world"))
                socket.send(
                    """{"type":"conversation.item.input_audio_transcription.completed","item_id":"item_1",
                       "content_index":0,"transcript":"Hello, world."}"""
                )
            }
        })
        val partials = mutableListOf<String>()

        val text = stt().transcribe(
            flowOf(byteArrayOf(1, 2), byteArrayOf(3, 4)),
            "gpt-live-transcribe",
            "en",
            partials::add
        )

        assertEquals("Hello, world.", text)
        assertEquals(listOf("Hello", "Hello world", "Hello, world."), partials)
        val request = server.takeRequest()
        assertEquals("Bearer sk-test", request.headers["Authorization"])
        assertEquals("/v1/realtime?intent=transcription", request.target)
        assertEquals(
            listOf(
                "session.update",
                "input_audio_buffer.append",
                "input_audio_buffer.append",
                "input_audio_buffer.append", // Silence padding up to the 100 ms minimum.
                "input_audio_buffer.commit"
            ),
            received.map(::type)
        )
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun shortAudioIsPaddedWithSilenceToTheMinimumCommit() = runBlocking {
        upgrade(onMessage = { socket, text ->
            if (type(text) == "input_audio_buffer.commit") {
                socket.send(COMMITTED)
                socket.send(completed(""))
            }
        })

        stt().transcribe(flowOf(byteArrayOf(1, 2)), "gpt-live-transcribe", "", {})

        val audio = received.filter { type(it) == "input_audio_buffer.append" }.map(::appendedAudio)
        assertEquals(listOf(2, MIN_COMMIT_BYTES - 2), audio.map { it.size })
        assertTrue(audio[1].all { it == 0.toByte() })
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun fullAudioIsNotPadded() = runBlocking {
        upgrade(onMessage = { socket, text ->
            if (type(text) == "input_audio_buffer.commit") {
                socket.send(COMMITTED)
                socket.send(completed(""))
            }
        })

        stt().transcribe(flowOf(ByteArray(MIN_COMMIT_BYTES)), "gpt-live-transcribe", "", {})

        assertEquals(1, received.count { type(it) == "input_audio_buffer.append" })
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun returnsWithoutWaitingForTheCloseHandshake() = runBlocking {
        upgrade(closeReplyDelayMs = 3_000, onMessage = { socket, text ->
            if (type(text) == "input_audio_buffer.commit") {
                socket.send(COMMITTED)
                socket.send(completed("Hi."))
            }
        })
        val start = System.nanoTime()

        val text = stt().transcribe(flowOf(byteArrayOf(1, 2)), "gpt-live-transcribe", "", {})

        assertEquals("Hi.", text)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000) // Didn't wait for the server's close reply.
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun webSocketReusesTheWarmedUpConnection() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).build())
        upgrade(onMessage = { socket, text ->
            if (type(text) == "input_audio_buffer.commit") {
                socket.send(COMMITTED)
                socket.send(completed("Hi."))
            }
        })
        val httpClient = OkHttpClient()
        val stt = stt(httpClient = httpClient)

        stt.warmUp()
        val warmUp = server.takeRequest()
        while (httpClient.connectionPool.idleConnectionCount() == 0) Thread.sleep(10) // Response consumed.
        stt.transcribe(flowOf(byteArrayOf(1, 2)), "gpt-live-transcribe", "", {})

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
        server.enqueue(MockResponse.Builder().code(401).build())
        upgrade(onMessage = { socket, text ->
            if (type(text) == "input_audio_buffer.commit") {
                socket.send(COMMITTED)
                socket.send(completed("Hi."))
            }
        })
        server.enqueue(MockResponse.Builder().code(401).build())
        val httpClient = OkHttpClient()
        val stt = stt(httpClient = httpClient)

        stt.warmUp()
        stt.warmUp() // Within the window: skipped.
        while (httpClient.connectionPool.idleConnectionCount() == 0) Thread.sleep(10)
        // The call may still be finishing after its connection is pooled; a skipped one would add a request.
        while (httpClient.dispatcher.runningCallsCount() > 0) Thread.sleep(10)
        assertEquals(1, server.requestCount)

        stt.transcribe(flowOf(byteArrayOf(1, 2)), "gpt-live-transcribe", "", {})
        stt.warmUp() // The session took the warmed connection: warms a new one.

        assertEquals("HEAD", server.takeRequest().method)
        assertEquals("websocket", server.takeRequest().headers["Upgrade"])
        assertEquals("HEAD", server.takeRequest().method)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun serverErrorEventFailsTheSession() {
        upgrade(onOpen = { it.send("""{"type":"error","error":{"type":"server_error","message":"Boom"}}""") })

        val error = assertThrows(SttException::class.java) {
            runBlocking { stt().transcribe(endlessAudio, "gpt-live-transcribe", "", {}) }
        }
        assertEquals("OpenAI error: Boom", error.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun rejectedHandshakeMapsToApiKeyError() {
        server.enqueue(MockResponse.Builder().code(401).build())

        val error = assertThrows(SttException::class.java) {
            runBlocking { stt().transcribe(endlessAudio, "gpt-live-transcribe", "", {}) }
        }
        assertEquals("OpenAI rejected the API key. Check it in Iris settings.", error.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun missingApiKeyFailsWithoutConnecting() {
        val error = assertThrows(SttException::class.java) {
            runBlocking { stt(apiKey = null).transcribe(endlessAudio, "gpt-live-transcribe", "", {}) }
        }
        assertEquals("Add your OpenAI API key in Iris settings", error.message)
        assertEquals(0, server.requestCount)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun deltasBeforeCommitThenCompletedAfterCommit() = runBlocking {
        upgrade(onMessage = { socket, text ->
            when (type(text)) {
                "input_audio_buffer.append" -> {
                    socket.send(delta("Hello"))
                    socket.send(delta(" world"))
                }
                "input_audio_buffer.commit" -> {
                    socket.send(COMMITTED)
                    socket.send(completed("Hello world."))
                }
            }
        })
        val partials = mutableListOf<String>()
        val deltasSeen = CompletableDeferred<Unit>()
        // Holds the commit back until both deltas have been reported.
        val audio = flow {
            emit(ByteArray(MIN_COMMIT_BYTES)) // A full commit, so no padding append triggers more deltas.
            deltasSeen.await()
        }

        val text = stt().transcribe(audio, "gpt-live-transcribe", "", { live ->
            partials += live
            if (live == "Hello world") deltasSeen.complete(Unit)
        })

        assertEquals("Hello world.", text)
        assertEquals(listOf("Hello", "Hello world", "Hello world."), partials)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun timesOutWaitingForTheFinalTranscript() {
        upgrade()

        val error = assertThrows(SttException::class.java) {
            runBlocking {
                stt(finalTimeoutMs = 200).transcribe(flowOf(byteArrayOf(1, 2)), "gpt-live-transcribe", "", {})
            }
        }
        assertEquals("Timed out waiting for the transcript", error.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun cancellingClosesTheSocket() = runBlocking {
        upgrade()
        val session = launch { stt().transcribe(endlessAudio, "gpt-live-transcribe", "", {}) }
        while (received.isEmpty()) delay(10) // Connected: the server got session.update.

        session.cancelAndJoin()

        assertTrue(serverSawClose.await(5, TimeUnit.SECONDS))
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun serverCloseMidSessionFailsTheSession() {
        upgrade(onMessage = { socket, text ->
            if (type(text) == "input_audio_buffer.append") socket.close(1011, "Going away")
        })
        val audio = flow {
            emit(byteArrayOf(1, 2))
            awaitCancellation()
        }

        val error = assertThrows(SttException::class.java) {
            runBlocking { stt().transcribe(audio, "gpt-live-transcribe", "", {}) }
        }
        assertEquals("OpenAI closed the connection: Going away", error.message)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun malformedEventsAreIgnored() = runBlocking {
        upgrade(
            onOpen = { socket ->
                socket.send("not json")
                socket.send("[1,2]")
                socket.send("""{"type":"input_audio_buffer.committed"}""")
                socket.send("""{"type":"conversation.item.input_audio_transcription.delta","item_id":{"id":1}}""")
            },
            onMessage = { socket, text ->
                if (type(text) == "input_audio_buffer.commit") {
                    socket.send(COMMITTED)
                    socket.send(completed("Still fine."))
                }
            }
        )

        val text = stt().transcribe(flowOf(byteArrayOf(1, 2)), "gpt-live-transcribe", "", {})

        assertEquals("Still fine.", text)
    }

    private fun appendedAudio(message: String): ByteArray =
        Base64.getDecoder().decode(Json.parseToJsonElement(message).jsonObject["audio"]!!.jsonPrimitive.content)

    private fun delta(text: String) =
        """{"type":"conversation.item.input_audio_transcription.delta","item_id":"item_1","delta":"$text"}"""

    private fun completed(text: String) =
        """{"type":"conversation.item.input_audio_transcription.completed","item_id":"item_1","transcript":"$text"}"""

    private companion object {
        const val TEST_TIMEOUT_MS = 15_000L
        const val COMMITTED = """{"type":"input_audio_buffer.committed","item_id":"item_1"}"""
    }
}
