/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.dictation

import androidx.datastore.preferences.core.emptyPreferences
import dev.jamius.iris.audio.AudioCaptureException
import dev.jamius.iris.audio.AudioChunk
import dev.jamius.iris.data.settings.Settings
import dev.jamius.iris.data.settings.toSettings
import dev.jamius.iris.stt.SpeechToTextProvider
import dev.jamius.iris.stt.SttException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationControllerTest {

    private val finals = mutableListOf<String>()
    private var audioReleased = false
    private var warmUps = 0
    private var recordedRate = 0

    /** A 100 ms chunk every 100 ms until cancelled. */
    private val audio: Flow<ByteArray> = flow {
        try {
            while (true) {
                emit(ByteArray(2))
                delay(100)
            }
        } finally {
            audioReleased = true
        }
    }

    /** Reports "chunk N" for each chunk, then returns [result] once the audio ends. */
    private class FakeStt(
        private val rate: Int = 24_000,
        private val result: suspend () -> String,
    ) : SpeechToTextProvider {
        var calls = 0
        var model = ""

        override fun sampleRate(model: String) = rate

        override suspend fun transcribe(
            audio: Flow<ByteArray>,
            model: String,
            language: String,
            onPartial: (String) -> Unit,
        ): String {
            calls++
            this.model = model
            var chunks = 0
            audio.collect { onPartial("chunk ${++chunks}") }
            return result()
        }

        override fun warmUp() = Unit
    }

    private fun TestScope.controller(
        stt: SpeechToTextProvider,
        audio: Flow<ByteArray> = this@DictationControllerTest.audio,
        cleanupEnabled: Boolean = false,
        level: Float = 0f,
        settings: Settings = emptyPreferences().toSettings(emptySet()).copy(cleanupEnabled = cleanupEnabled),
        onFinalTranscript: suspend (String) -> DictationState.Done = { text ->
            finals += text
            DictationState.Done(text)
        },
    ) = DictationController(
        scope = backgroundScope,
        // Virtual time is the clock: a chunk's read ends when it is emitted.
        audio = { rate ->
            recordedRate = rate
            audio.map { AudioChunk(it, currentTime, level) }
        },
        stt = { stt },
        settings = flowOf(settings),
        warmUpCleanup = { warmUps++ },
        onFinalTranscript = onFinalTranscript,
        clock = { currentTime },
    )

    @Test
    fun startThenStopFinalizesToDoneThenIdle() = runTest {
        val stt = FakeStt { "Hello world" }
        val controller = controller(stt)

        controller.start()
        runCurrent()
        assertEquals(DictationState.Listening("chunk 1"), controller.state.value)
        advanceTimeBy(150)
        assertEquals(DictationState.Listening("chunk 2"), controller.state.value)

        controller.stop()
        assertEquals(DictationState.Finalizing("chunk 2"), controller.state.value)
        // The chunk recorded during the stop is still sent, then the audio ends.
        advanceTimeBy(100)
        assertEquals(DictationState.Done("Hello world"), controller.state.value)
        assertEquals(listOf("Hello world"), finals)
        assertTrue(audioReleased)
        assertEquals("gpt-live-transcribe", stt.model)

        advanceTimeBy(DONE_DISPLAY_MS + 1)
        assertEquals(DictationState.Idle, controller.state.value)
    }

    @Test
    fun sessionRecordsAtTheProvidersRateForTheActiveModel() = runTest {
        val stt = FakeStt(rate = 16_000) { "Hi" }
        val settings = emptyPreferences().toSettings(emptySet()).copy(
            sttProviderId = Settings.STT_PROVIDER_VERCEL_GATEWAY,
            gatewaySttModel = "google/gemini-3.5-transcribe-live"
        )
        val controller = controller(stt, settings = settings)

        controller.start()
        runCurrent()
        controller.stop()
        advanceTimeBy(101) // The chunk recorded during the stop ends the audio.

        assertEquals(DictationState.Done("Hi"), controller.state.value)
        assertEquals(16_000, recordedRate)
        assertEquals("google/gemini-3.5-transcribe-live", stt.model)
    }

    @Test
    fun audioEndsOnStopWithoutWaitingForTheMicRelease() = runTest {
        var audioEndedAt = -1L
        val controller = controller(
            FakeStt {
                audioEndedAt = currentTime
                "Hi"
            },
            audio = flow {
                try {
                    while (true) {
                        emit(ByteArray(2))
                        delay(100)
                    }
                } finally {
                    withContext(NonCancellable) { delay(1_000) } // A slow mic release.
                    audioReleased = true
                }
            }
        )

        controller.start()
        runCurrent()
        advanceTimeBy(150)
        controller.stop()
        advanceTimeBy(100)
        // Ended right after the chunk recorded during the tap (t = 200), not after the release.
        assertEquals(200L, audioEndedAt)
        assertEquals(DictationState.Finalizing("chunk 3"), controller.state.value)

        advanceTimeBy(1_000) // The session still waits for the release before it finishes.
        assertTrue(audioReleased)
        assertEquals(DictationState.Done("Hi"), controller.state.value)
    }

    @Test
    fun chunksQueuedBeforeTheStopTapAreAllSent() = runTest {
        var sent = 0
        val controller = controller(object : SpeechToTextProvider {
            override suspend fun transcribe(
                audio: Flow<ByteArray>,
                model: String,
                language: String,
                onPartial: (String) -> Unit,
            ): String {
                audio.collect { if (++sent == 1) delay(1_000) } // A stall: chunks queue up meanwhile.
                return "Hi"
            }

            override fun warmUp() = Unit
        })

        controller.start()
        runCurrent()
        advanceTimeBy(950)
        controller.stop()
        advanceTimeBy(100)
        // The 10 chunks read before the tap (t = 0..900) and the one in progress (t = 1000).
        assertEquals(11, sent)
        assertEquals(DictationState.Done("Hi"), controller.state.value)
    }

    @Test
    fun cancelWhileFinalizingDiscardsSession() = runTest {
        val controller = controller(FakeStt {
            delay(1_000)
            "discarded"
        })

        controller.start()
        runCurrent()
        controller.stop()
        advanceTimeBy(150)
        controller.cancel()
        assertEquals(DictationState.Idle, controller.state.value)

        advanceTimeBy(10_000)
        assertEquals(DictationState.Idle, controller.state.value)
        assertTrue(finals.isEmpty())
    }

    @Test
    fun startAndStopAreIgnoredWhileFinalizing() = runTest {
        val stt = FakeStt {
            delay(10_000)
            "Hi"
        }
        val controller = controller(stt)

        controller.start()
        runCurrent()
        controller.stop()
        advanceTimeBy(150)
        controller.start()
        controller.stop()
        runCurrent()
        assertTrue(controller.state.value is DictationState.Finalizing)
        assertEquals(1, stt.calls)

        advanceTimeBy(10_000)
        assertEquals(DictationState.Done("Hi"), controller.state.value)
        assertEquals(listOf("Hi"), finals)
    }

    @Test
    fun stopIsIgnoredUnlessListening() = runTest {
        val controller = controller(FakeStt { "Hi" })

        controller.stop()
        runCurrent()
        assertEquals(DictationState.Idle, controller.state.value)

        controller.start()
        runCurrent()
        controller.stop()
        advanceTimeBy(101)
        assertEquals(DictationState.Done("Hi"), controller.state.value)
        controller.stop()
        assertEquals(DictationState.Done("Hi"), controller.state.value)
    }

    @Test
    fun levelFollowsChunksWhileListeningAndResetsOnStop() = runTest {
        val controller = controller(FakeStt { "Hi" }, level = 0.5f)

        controller.start()
        runCurrent()
        assertEquals(0.5f, controller.level.value)
        controller.stop()
        assertEquals(0f, controller.level.value)
        advanceTimeBy(101) // The chunk in progress at stop is sent but doesn't set the level.
        assertEquals(DictationState.Done("Hi"), controller.state.value)
        assertEquals(0f, controller.level.value)

        controller.start()
        runCurrent()
        assertEquals(0.5f, controller.level.value)
        controller.cancel()
        assertEquals(0f, controller.level.value)
    }

    @Test
    fun startIsIgnoredWhileListening() = runTest {
        val stt = FakeStt { "Hi" }
        val controller = controller(stt)

        controller.start()
        runCurrent()
        advanceTimeBy(150)
        controller.start()
        runCurrent()
        assertEquals(DictationState.Listening("chunk 2"), controller.state.value)
        assertEquals(1, stt.calls)
    }

    @Test
    fun levelResetsOnSttErrorWhileListening() = runTest {
        val controller = controller(
            object : SpeechToTextProvider {
                override suspend fun transcribe(
                    audio: Flow<ByteArray>,
                    model: String,
                    language: String,
                    onPartial: (String) -> Unit,
                ): String {
                    var chunks = 0
                    audio.collect { if (++chunks == 2) throw SttException("OpenAI error: Boom") }
                    return "unused"
                }

                override fun warmUp() = Unit
            },
            level = 0.5f
        )

        controller.start()
        runCurrent()
        assertEquals(0.5f, controller.level.value)
        advanceTimeBy(101)
        assertEquals(DictationState.Error("OpenAI error: Boom"), controller.state.value)
        assertEquals(0f, controller.level.value)
    }

    @Test
    fun startWhileDoneStartsNewSessionThatTheDisplayTimerLeavesAlone() = runTest {
        val stt = FakeStt { "Hi" }
        val controller = controller(stt)

        controller.start()
        runCurrent()
        controller.stop()
        advanceTimeBy(101)
        assertEquals(DictationState.Done("Hi"), controller.state.value)

        controller.start()
        runCurrent()
        assertEquals(DictationState.Listening("chunk 1"), controller.state.value)
        advanceTimeBy(DONE_DISPLAY_MS + 1)
        assertTrue(controller.state.value is DictationState.Listening)
        assertEquals(2, stt.calls)
    }

    @Test
    fun startWhileErrorStartsNewSessionThatTheDisplayTimerLeavesAlone() = runTest {
        val stt = FakeStt { "" }
        val controller = controller(stt)

        controller.start()
        runCurrent()
        controller.stop()
        advanceTimeBy(101)
        assertEquals(DictationState.Error("No speech detected"), controller.state.value)

        controller.start()
        runCurrent()
        assertEquals(DictationState.Listening("chunk 1"), controller.state.value)
        advanceTimeBy(ERROR_DISPLAY_MS + 1)
        assertTrue(controller.state.value is DictationState.Listening)
        assertEquals(2, stt.calls)
    }

    @Test
    fun recordingErrorShowsMessageThenIdle() = runTest {
        val controller = controller(
            FakeStt { "unused" },
            audio = flow { throw AudioCaptureException("Microphone permission is missing.") }
        )

        controller.start()
        runCurrent()
        assertEquals(DictationState.Error("Microphone permission is missing."), controller.state.value)

        advanceTimeBy(ERROR_DISPLAY_MS + 1)
        assertEquals(DictationState.Idle, controller.state.value)
        assertTrue(finals.isEmpty())
    }

    @Test
    fun providerErrorShowsMessage() = runTest {
        val controller = controller(FakeStt { throw SttException("OpenAI error: Boom") })

        controller.start()
        runCurrent()
        controller.stop()
        advanceTimeBy(101)
        assertEquals(DictationState.Error("OpenAI error: Boom"), controller.state.value)
        assertTrue(finals.isEmpty())
    }

    @Test
    fun cancelWhileListeningDiscardsSession() = runTest {
        val controller = controller(FakeStt { "discarded" })

        controller.start()
        runCurrent()
        controller.cancel()
        runCurrent()
        assertEquals(DictationState.Idle, controller.state.value)
        assertTrue(audioReleased)

        advanceTimeBy(10_000)
        assertEquals(DictationState.Idle, controller.state.value)
        assertTrue(finals.isEmpty())
    }

    @Test
    fun finalTranscriptHookExceptionShowsGenericError() = runTest {
        val controller = controller(
            FakeStt { "Hi" },
            onFinalTranscript = { _ -> throw IllegalStateException("boom") }
        )

        controller.start()
        runCurrent()
        controller.stop()
        advanceTimeBy(101)
        assertEquals(DictationState.Error("Something went wrong"), controller.state.value)
    }

    @Test
    fun doneWithNoticeShowsItForLonger() = runTest {
        val done = DictationState.Done("Hi", notice = "Copied to clipboard")
        val controller = controller(FakeStt { "Hi" }, onFinalTranscript = { _ -> done })

        controller.start()
        runCurrent()
        controller.stop()
        advanceTimeBy(101)
        assertEquals(done, controller.state.value)

        advanceTimeBy(DONE_DISPLAY_MS + 1)
        assertEquals(done, controller.state.value)
        advanceTimeBy(ERROR_DISPLAY_MS - DONE_DISPLAY_MS)
        assertEquals(DictationState.Idle, controller.state.value)
    }

    @Test
    fun focusLostWhileListeningDiscardsSession() = runTest {
        val controller = controller(FakeStt { "discarded" })

        controller.start()
        runCurrent()
        controller.onFocusLost()
        runCurrent()
        assertEquals(DictationState.Idle, controller.state.value)
        assertTrue(audioReleased)
        advanceTimeBy(10_000)
        assertTrue(finals.isEmpty())
    }

    @Test
    fun focusLostWhileFinalizingFinishesSession() = runTest {
        val controller = controller(FakeStt {
            delay(1_000)
            "Hi"
        })

        controller.start()
        runCurrent()
        controller.stop()
        advanceTimeBy(150)
        controller.onFocusLost()
        assertEquals(DictationState.Finalizing("chunk 2"), controller.state.value)

        advanceTimeBy(1_000)
        assertEquals(DictationState.Done("Hi"), controller.state.value)
        assertEquals(listOf("Hi"), finals)
    }

    @Test
    fun cleanupIsWarmedUpOnStartAndStopWhenEnabled() = runTest {
        val controller = controller(FakeStt { "Hi" }, cleanupEnabled = true)

        controller.start()
        runCurrent()
        assertEquals(1, warmUps)
        controller.stop()
        assertEquals(2, warmUps)
        advanceTimeBy(101)
        assertEquals(DictationState.Done("Hi"), controller.state.value)
        assertEquals(2, warmUps)
    }

    @Test
    fun cleanupIsNotWarmedUpWhenDisabled() = runTest {
        val controller = controller(FakeStt { "Hi" }, cleanupEnabled = false)

        controller.start()
        runCurrent()
        controller.stop()
        advanceTimeBy(101)
        assertEquals(DictationState.Done("Hi"), controller.state.value)
        assertEquals(0, warmUps)
    }
}
