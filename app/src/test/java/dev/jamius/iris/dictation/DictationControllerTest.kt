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
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

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

    /** Reports "chunk N" for each chunk (if it [streams]), then returns [result] once the audio ends. */
    private class FakeStt(
        private val rate: Int = 24_000,
        override val streams: Boolean = true,
        private val live: Boolean = streams,
        private val result: suspend () -> String,
    ) : SpeechToTextProvider {
        var calls = 0
        var model = ""
        var timeoutMs = 0L
        val received = mutableListOf<ByteArray>()

        override fun sampleRate(model: String) = rate

        override fun liveText(model: String) = live

        override suspend fun transcribe(
            audio: Flow<ByteArray>,
            model: String,
            language: String,
            timeoutMs: Long,
            onPartial: (String) -> Unit,
        ): String {
            calls++
            this.model = model
            this.timeoutMs = timeoutMs
            var chunks = 0
            audio.collect {
                received += it
                chunks++
                if (streams) onPartial("chunk $chunks")
            }
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
        vadModel: ((FloatArray) -> Float)? = null,
        onFinalTranscript: suspend (String) -> DictationState.Done = { text ->
            finals += text
            DictationState.Done(text)
        },
    ) = DictationController(
        scope = backgroundScope,
        // Virtual time is the clock: a chunk's read ends when it is emitted.
        audio = { rate ->
            recordedRate = rate
            audio.map { AudioChunk(it, currentTime * NANOS_PER_MS, level) }
        },
        stt = { stt },
        // VAD only for tests that pass a model.
        settings = flowOf(settings.copy(vadEnabled = vadModel != null)),
        warmUpCleanup = { warmUps++ },
        onFinalTranscript = onFinalTranscript,
        vadModel = { vadModel ?: error("VAD off") },
        // Gate in the test's thread, so virtual time stays deterministic.
        vadContext = EmptyCoroutineContext,
        clock = { currentTime * NANOS_PER_MS },
    )

    /** One 32 ms frame at 16 kHz every 32 ms until cancelled, the Nth (from 0) tagged with N in its first byte. */
    private val frames: Flow<ByteArray> = flow {
        try {
            var n = 0
            while (true) {
                emit(ByteArray(1024).also { it[0] = n++.toByte() })
                delay(32)
            }
        } finally {
            audioReleased = true
        }
    }

    /** The model is called once per frame, in order: frames [voiced] are speech. */
    private fun speechAt(vararg voiced: IntRange): (FloatArray) -> Float {
        var calls = 0
        return {
            val frame = calls++
            if (voiced.any { frame in it }) 1f else 0f
        }
    }

    /** [frames], never all zeros (a keep-alive chunk is). */
    private val markedFrames = frames.map { it.also { bytes -> bytes[1] = 1 } }

    private fun isZeros(chunk: ByteArray) = chunk.all { it == 0.toByte() }

    private fun tags(chunks: List<ByteArray>) = chunks.map { it[0].toInt() and 0xFF }

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
    fun sessionPassesTheSttTimeoutSetting() = runTest {
        val stt = FakeStt { "Hi" }
        val settings = emptyPreferences().toSettings(emptySet()).copy(sttTimeoutSeconds = 20)
        val controller = controller(stt, settings = settings)

        controller.start()
        runCurrent()
        controller.stop()
        advanceTimeBy(101)

        assertEquals(DictationState.Done("Hi"), controller.state.value)
        assertEquals(20_000L, stt.timeoutMs)
    }

    @Test
    fun chunksQueuedBeforeTheStopTapAreAllSent() = runTest {
        var sent = 0
        val controller = controller(object : SpeechToTextProvider {
            override suspend fun transcribe(
                audio: Flow<ByteArray>,
                model: String,
                language: String,
                timeoutMs: Long,
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
                    timeoutMs: Long,
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

    @Test
    fun stopDuringSilenceEndsAtOnceWithNoSpeechAndSendsNothing() = runTest {
        val stt = FakeStt(rate = 16_000) {
            delay(10_000) // Never reached: no STT wait.
            "unused"
        }
        val controller = controller(stt, audio = frames, vadModel = { 0f })

        controller.start()
        runCurrent()
        advanceTimeBy(1_000)
        controller.stop()
        advanceTimeBy(33) // The frame read during the tap ends the audio.

        assertEquals(DictationState.Error("No speech detected"), controller.state.value)
        assertTrue(stt.received.isEmpty())
        assertTrue(audioReleased)
    }

    @Test
    fun levelFollowsEveryChunkWhileTheGateSendsNothing() = runTest {
        val stt = FakeStt(rate = 16_000) { "unused" }
        val controller = controller(stt, audio = frames, level = 0.5f, vadModel = { 0f })

        controller.start()
        runCurrent()
        advanceTimeBy(500)
        assertEquals(0.5f, controller.level.value)
        assertTrue(stt.received.isEmpty())
    }

    @Test
    fun streamingProviderGetsPreRollSpeechAndTheStreamingHangover() = runTest {
        val stt = FakeStt(rate = 16_000) { "Hi" }
        val controller = controller(stt, audio = frames, vadModel = speechAt(20..29))

        controller.start()
        runCurrent()
        advanceTimeBy(100 * 32L + 10)
        controller.stop()
        advanceTimeBy(32)

        assertEquals(DictationState.Done("Hi"), controller.state.value)
        // 450 ms pre-roll (frames 6..20, the first voiced one included), speech up to 29, then 52 hangover frames.
        assertEquals((6..81).toList(), tags(stt.received))
    }

    @Test
    fun uploadingProviderGetsTheOfflineHangover() = runTest {
        val stt = FakeStt(rate = 16_000, streams = false) { "Hi" }
        val controller = controller(stt, audio = frames, vadModel = speechAt(20..29))

        controller.start()
        runCurrent()
        advanceTimeBy(100 * 32L + 10)
        controller.stop()
        advanceTimeBy(32)

        assertEquals(DictationState.Done("Hi"), controller.state.value)
        assertEquals((6..44).toList(), tags(stt.received))
    }

    @Test
    fun streamingProviderWithoutLiveTextGetsTheOfflineHangover() = runTest {
        val stt = FakeStt(rate = 16_000, live = false) { "Hi" }
        val controller = controller(stt, audio = frames, vadModel = speechAt(20..29))

        controller.start()
        runCurrent()
        advanceTimeBy(100 * 32L + 10)
        controller.stop()
        advanceTimeBy(32)

        assertEquals(DictationState.Done("Hi"), controller.state.value)
        assertEquals((6..44).toList(), tags(stt.received))
    }

    @Test
    fun vadAt24kHzSendsPreRollSpeechAndTheStreamingHangover() = runTest {
        val stt = FakeStt { "Hi" }
        // 1200-sample (50 ms) chunks at 24 kHz, the Nth tagged with N: 1.5625 VAD frames each.
        val chunks = flow {
            var n = 0
            while (true) {
                emit(ByteArray(2_400).also { it[0] = n++.toByte() })
                delay(50)
            }
        }
        val controller = controller(stt, audio = chunks, vadModel = speechAt(20..29))

        controller.start()
        runCurrent()
        advanceTimeBy(60 * 50L + 10)
        controller.stop()
        advanceTimeBy(50)

        assertEquals(DictationState.Done("Hi"), controller.state.value)
        assertEquals(24_000, recordedRate)
        // Onset at frame 21 (chunk 14) with 9 chunks (450 ms) of pre-roll; the 52nd hangover frame (81) completes in
        // chunk 52.
        assertEquals((5..52).toList(), tags(stt.received))
    }

    @Test
    fun streamingProviderGetsAChunkOfZerosEvery5sOfWithheldAudio() = runTest {
        val stt = FakeStt(rate = 16_000) { "Hi" }
        val controller = controller(stt, audio = markedFrames, vadModel = speechAt(20..29, 460..469))

        controller.start()
        runCurrent()
        advanceTimeBy(530 * 32L + 10)
        controller.stop()
        advanceTimeBy(32)

        assertEquals(DictationState.Done("Hi"), controller.state.value)
        // Speech 6..81 (76 chunks), last sent at 2.59 s; zeros at frames 238 (7.62 s) and 395 (12.64 s); speech
        // again from 446 (pre-roll) to 521 (76 chunks).
        assertEquals(154, stt.received.size)
        assertEquals(listOf(76, 77), stt.received.indices.filter { isZeros(stt.received[it]) })
        assertEquals(1_024, stt.received[76].size)
    }

    @Test
    fun uploadingProviderGetsNoKeepAlive() = runTest {
        val stt = FakeStt(rate = 16_000, streams = false) { "Hi" }
        val controller = controller(stt, audio = markedFrames, vadModel = speechAt(20..29, 460..469))

        controller.start()
        runCurrent()
        advanceTimeBy(530 * 32L + 10)
        controller.stop()
        advanceTimeBy(32)

        assertEquals(DictationState.Done("Hi"), controller.state.value)
        assertTrue(stt.received.isNotEmpty())
        assertTrue(stt.received.none(::isZeros))
    }

    @Test
    fun keepAliveZerosDontCountAsSpeech() = runTest {
        val stt = FakeStt(rate = 16_000) {
            delay(10_000) // Never reached: no STT wait.
            "unused"
        }
        val controller = controller(stt, audio = markedFrames, vadModel = { 0f })

        controller.start()
        runCurrent()
        advanceTimeBy(6_000)
        controller.stop()
        advanceTimeBy(33)

        assertEquals(DictationState.Error("No speech detected"), controller.state.value)
        // Only the keep-alive at 5 s reached the provider.
        assertEquals(1, stt.received.size)
        assertTrue(isZeros(stt.received[0]))
    }

    @Test
    fun vadOffSendsEveryChunk() = runTest {
        val stt = FakeStt(rate = 16_000) { "Hi" }
        val controller = controller(stt, audio = frames) // vadModel would throw if called.

        controller.start()
        runCurrent()
        advanceTimeBy(10 * 32L + 10)
        controller.stop()
        advanceTimeBy(32)

        assertEquals(DictationState.Done("Hi"), controller.state.value)
        assertEquals((0..11).toList(), tags(stt.received))
    }

    @Test
    fun failingVadModelSendsEveryChunk() = runTest {
        val stt = FakeStt(rate = 16_000) { "Hi" }
        val controller = controller(stt, audio = frames, vadModel = { throw IllegalStateException("No model") })

        controller.start()
        runCurrent()
        advanceTimeBy(10 * 32L + 10)
        controller.stop()
        advanceTimeBy(32)

        assertEquals(DictationState.Done("Hi"), controller.state.value)
        assertEquals((0..11).toList(), tags(stt.received))
    }

    @Test
    fun uploadingProviderShowsNoLiveTextThenTheFinalText() = runTest {
        val stt = FakeStt(rate = 16_000, streams = false) {
            delay(500) // The upload.
            "Hello world"
        }
        val controller = controller(stt, audio = frames)

        controller.start()
        runCurrent()
        advanceTimeBy(1_000)
        assertEquals(DictationState.Listening(""), controller.state.value)
        controller.stop()
        advanceTimeBy(100)
        assertEquals(DictationState.Finalizing(""), controller.state.value)

        advanceTimeBy(500)
        assertEquals(DictationState.Done("Hello world"), controller.state.value)
        assertEquals(listOf("Hello world"), finals)
        assertEquals(16_000, recordedRate)
    }

    /** A 50 ms chunk of a 200 Hz sine at 16 kHz every 50 ms until cancelled; [sent] collects them. */
    private fun sine(sent: MutableList<ByteArray>): Flow<ByteArray> = flow {
        var n = 0
        while (true) {
            val chunk = ByteArray(1_600)
            for (i in 0 until 800) {
                val sample = (sin(2 * PI * 200 * (n * 800 + i) / 16_000) * 10_000).roundToInt()
                chunk[2 * i] = sample.toByte()
                chunk[2 * i + 1] = (sample shr 8).toByte()
            }
            n++
            sent += chunk
            emit(chunk)
            delay(50)
        }
    }

    private fun speed(percent: Int) = emptyPreferences().toSettings(emptySet()).copy(sttSpeedPercent = percent)

    @Test
    fun speed100SendsTheGatedArraysUnchanged() = runTest {
        val stt = FakeStt(rate = 16_000) { "Hi" }
        val recorded = mutableListOf<ByteArray>()
        val controller = controller(
            stt,
            audio = frames.map { it.also { bytes -> recorded += bytes } },
            settings = speed(100),
            vadModel = speechAt(20..29)
        )

        controller.start()
        runCurrent()
        advanceTimeBy(100 * 32L + 10)
        controller.stop()
        advanceTimeBy(32)

        assertEquals(DictationState.Done("Hi"), controller.state.value)
        assertEquals((6..81).toList(), tags(stt.received))
        stt.received.forEach { assertSame(recorded[it[0].toInt() and 0xFF], it) }
    }

    @Test
    fun speed200SendsHalfTheAudioIncludingTheFlushedTail() = runTest {
        val stt = FakeStt(rate = 16_000) { "Hi" }
        val sent = mutableListOf<ByteArray>()
        val controller = controller(stt, audio = sine(sent), settings = speed(200))

        controller.start()
        runCurrent()
        advanceTimeBy(2_000)
        controller.stop()
        advanceTimeBy(51) // The chunk recorded during the stop ends the audio.

        assertEquals(DictationState.Done("Hi"), controller.state.value)
        val expected = sent.sumOf { it.size } / 2.0
        // The flush tail (~12 ms = ~380 bytes) is part of what the provider got before its audio ended.
        assertEquals(expected, stt.received.sumOf { it.size }.toDouble(), 100.0)
        assertTrue(stt.received.none { it.isEmpty() })
    }

    @Test
    fun speed200WithoutSpeechStillEndsWithNoSpeechAndSendsNothing() = runTest {
        val stt = FakeStt(rate = 16_000) {
            delay(10_000) // Never reached: no STT wait.
            "unused"
        }
        val controller = controller(stt, audio = frames, settings = speed(200), vadModel = { 0f })

        controller.start()
        runCurrent()
        advanceTimeBy(1_000)
        controller.stop()
        advanceTimeBy(33)

        assertEquals(DictationState.Error("No speech detected"), controller.state.value)
        assertTrue(stt.received.isEmpty())
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
