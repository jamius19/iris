/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.dictation

import androidx.datastore.preferences.core.emptyPreferences
import dev.jamius.iris.audio.AudioCaptureException
import dev.jamius.iris.data.settings.toSettings
import dev.jamius.iris.stt.SpeechToTextProvider
import dev.jamius.iris.stt.SttException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationControllerTest {

    private val finals = mutableListOf<String>()
    private var audioReleased = false
    private var warmUps = 0

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
    private class FakeStt(private val result: suspend () -> String) : SpeechToTextProvider {
        var calls = 0
        var model = ""

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
    }

    private fun TestScope.controller(
        stt: SpeechToTextProvider,
        audio: Flow<ByteArray> = this@DictationControllerTest.audio,
        cleanupEnabled: Boolean = false,
        onFinalTranscript: suspend (String, Boolean) -> DictationState.Done = { text, _ ->
            finals += text
            DictationState.Done(text)
        },
    ) = DictationController(
        scope = backgroundScope,
        audio = audio,
        stt = stt,
        settings = flowOf(emptyPreferences().toSettings(emptySet()).copy(cleanupEnabled = cleanupEnabled)),
        warmUpCleanup = { warmUps++ },
        onFinalTranscript = onFinalTranscript,
    )

    @Test
    fun tapStartsThenTapFinalizesToDoneThenIdle() = runTest {
        val stt = FakeStt { "Hello world" }
        val controller = controller(stt)

        controller.onTap()
        runCurrent()
        assertEquals(DictationState.Listening("chunk 1"), controller.state.value)
        advanceTimeBy(150)
        assertEquals(DictationState.Listening("chunk 2"), controller.state.value)

        controller.onTap()
        assertEquals(DictationState.Finalizing("chunk 2"), controller.state.value)
        // The chunk recorded during the tap is still sent, then the audio ends.
        advanceTimeBy(100)
        assertEquals(DictationState.Done("Hello world"), controller.state.value)
        assertEquals(listOf("Hello world"), finals)
        assertTrue(audioReleased)
        assertEquals("gpt-live-transcribe", stt.model)

        advanceTimeBy(DONE_DISPLAY_MS + 1)
        assertEquals(DictationState.Idle, controller.state.value)
    }

    @Test
    fun tapSoonAfterStopWhileFinalizingIsIgnored() = runTest {
        val controller = controller(FakeStt {
            delay(10_000)
            "Hi"
        })

        controller.onTap()
        runCurrent()
        controller.onTap()
        advanceTimeBy(150)
        controller.onTap() // Double tap on stop.
        assertEquals(DictationState.Finalizing("chunk 2"), controller.state.value)
        advanceTimeBy(FINALIZE_TAP_GRACE_MS - 200)
        controller.onTap()
        assertEquals(DictationState.Finalizing("chunk 2"), controller.state.value)

        advanceTimeBy(6_000)
        assertEquals(DictationState.Done("Hi"), controller.state.value)
        assertEquals(listOf("Hi"), finals)
    }

    @Test
    fun tapWhileFinalizingStallsCancelsToIdle() = runTest {
        val stt = FakeStt {
            delay(60_000)
            "discarded"
        }
        val controller = controller(stt)

        controller.onTap()
        runCurrent()
        controller.onTap()
        advanceTimeBy(FINALIZE_TAP_GRACE_MS + 1)
        assertEquals(DictationState.Finalizing("chunk 2"), controller.state.value)
        controller.onTap()
        assertEquals(DictationState.Idle, controller.state.value)

        advanceTimeBy(10_000)
        assertEquals(DictationState.Idle, controller.state.value)
        assertEquals(1, stt.calls)
        assertTrue(finals.isEmpty())
    }

    @Test
    fun cancelWhileFinalizingDiscardsSession() = runTest {
        val controller = controller(FakeStt {
            delay(1_000)
            "discarded"
        })

        controller.onTap()
        runCurrent()
        controller.onTap()
        advanceTimeBy(150)
        controller.cancel()
        assertEquals(DictationState.Idle, controller.state.value)

        advanceTimeBy(10_000)
        assertEquals(DictationState.Idle, controller.state.value)
        assertTrue(finals.isEmpty())
    }

    @Test
    fun tapWhileDoneStartsNewSessionThatTheDisplayTimerLeavesAlone() = runTest {
        val stt = FakeStt { "Hi" }
        val controller = controller(stt)

        controller.onTap()
        runCurrent()
        controller.onTap()
        advanceTimeBy(101)
        assertEquals(DictationState.Done("Hi"), controller.state.value)

        controller.onTap()
        runCurrent()
        assertEquals(DictationState.Listening("chunk 1"), controller.state.value)
        advanceTimeBy(DONE_DISPLAY_MS + 1)
        assertTrue(controller.state.value is DictationState.Listening)
        assertEquals(2, stt.calls)
    }

    @Test
    fun tapWhileErrorStartsNewSessionThatTheDisplayTimerLeavesAlone() = runTest {
        val stt = FakeStt { "" }
        val controller = controller(stt)

        controller.onTap()
        runCurrent()
        controller.onTap()
        advanceTimeBy(101)
        assertEquals(DictationState.Error("No speech detected"), controller.state.value)

        controller.onTap()
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

        controller.onTap()
        runCurrent()
        assertEquals(DictationState.Error("Microphone permission is missing."), controller.state.value)

        advanceTimeBy(ERROR_DISPLAY_MS + 1)
        assertEquals(DictationState.Idle, controller.state.value)
        assertTrue(finals.isEmpty())
    }

    @Test
    fun providerErrorShowsMessage() = runTest {
        val controller = controller(FakeStt { throw SttException("OpenAI error: Boom") })

        controller.onTap()
        runCurrent()
        controller.onTap()
        advanceTimeBy(101)
        assertEquals(DictationState.Error("OpenAI error: Boom"), controller.state.value)
        assertTrue(finals.isEmpty())
    }

    @Test
    fun cancelWhileListeningDiscardsSession() = runTest {
        val controller = controller(FakeStt { "discarded" })

        controller.onTap()
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
            onFinalTranscript = { _, _ -> throw IllegalStateException("boom") }
        )

        controller.onTap()
        runCurrent()
        controller.onTap()
        advanceTimeBy(101)
        assertEquals(DictationState.Error("Something went wrong"), controller.state.value)
    }

    @Test
    fun doneWithNoticeShowsItForLonger() = runTest {
        val done = DictationState.Done("Hi", notice = "Copied to clipboard")
        val controller = controller(FakeStt { "Hi" }, onFinalTranscript = { _, _ -> done })

        controller.onTap()
        runCurrent()
        controller.onTap()
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

        controller.onTap()
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

        controller.onTap()
        runCurrent()
        controller.onTap()
        advanceTimeBy(150)
        controller.onFocusLost()
        assertEquals(DictationState.Finalizing("chunk 2"), controller.state.value)

        advanceTimeBy(1_000)
        assertEquals(DictationState.Done("Hi"), controller.state.value)
        assertEquals(listOf("Hi"), finals)
    }

    @Test
    fun tapDuringCleanupInsertsRawTranscript() = runTest {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val controller = controller(FakeStt { "Hi" }, onFinalTranscript = { text, skipCleanup ->
            calls += text to skipCleanup
            if (!skipCleanup) delay(1_000) // Cleanup.
            DictationState.Done(if (skipCleanup) text else "Cleaned")
        })

        controller.onTap()
        runCurrent()
        controller.onTap()
        advanceTimeBy(150)
        assertTrue(controller.state.value is DictationState.Finalizing)
        controller.onTap()
        runCurrent()
        assertEquals(DictationState.Done("Hi"), controller.state.value)
        assertEquals(listOf("Hi" to false, "Hi" to true), calls)
    }

    @Test
    fun cleanupIsWarmedUpOnStartAndStopWhenEnabled() = runTest {
        val controller = controller(FakeStt { "Hi" }, cleanupEnabled = true)

        controller.onTap()
        runCurrent()
        assertEquals(1, warmUps)
        controller.onTap()
        assertEquals(2, warmUps)
        advanceTimeBy(101)
        assertEquals(DictationState.Done("Hi"), controller.state.value)
        assertEquals(2, warmUps)
    }

    @Test
    fun cleanupIsNotWarmedUpWhenDisabled() = runTest {
        val controller = controller(FakeStt { "Hi" }, cleanupEnabled = false)

        controller.onTap()
        runCurrent()
        controller.onTap()
        advanceTimeBy(101)
        assertEquals(DictationState.Done("Hi"), controller.state.value)
        assertEquals(0, warmUps)
    }
}
