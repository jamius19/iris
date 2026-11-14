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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
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
    private val warmUpSettings = mutableListOf<Settings>()

    /** Cloud defaults: the tests below were written against OpenAI, the default before on device. */
    private val openAi = emptyPreferences().toSettings(emptySet()).copy(sttProviderId = Settings.STT_PROVIDER_OPENAI)

    private var recordedRate = 0
    private val records = mutableListOf<Pair<Settings, DictationRecord>>()

    /** The final-transcript hook's and the record's calls, in order. */
    private val events = mutableListOf<String>()

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
        override val allowsSpeedUp: Boolean = true,
        override val wantsSegmentEnds: Boolean = false,
        private val result: suspend () -> String,
    ) : SpeechToTextProvider {
        var calls = 0
        var model = ""
        var language = ""
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
            this.language = language
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
        settings: Settings = openAi.copy(cleanupEnabled = cleanupEnabled),
        vadModel: ((FloatArray) -> Float)? = null,
        onFinalTranscript: suspend (String) -> DictationState.Done = { text ->
            finals += text
            DictationState.Done(text)
        },
        detectLanguage: suspend (String) -> String? = { null },
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
        warmUpCleanup = {
            warmUps++
            warmUpSettings += it
        },
        onFinalTranscript = onFinalTranscript,
        vadModel = { vadModel ?: error("VAD off") },
        // Gate in the test's thread, so virtual time stays deterministic.
        vadContext = EmptyCoroutineContext,
        clock = { currentTime * NANOS_PER_MS },
        record = { s, d ->
            events += "record"
            records += s to d
        },
        detectLanguage = detectLanguage,
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
        val settings = openAi.copy(
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
    fun sessionPassesTheActiveProvidersLanguage() = runTest {
        val cases = listOf(Settings.STT_PROVIDER_OPENAI to "fr", Settings.STT_PROVIDER_SONIOX to "en, bn")
        for ((provider, expected) in cases) {
            val stt = FakeStt { "Hi" }
            val settings = openAi.copy(
                sttProviderId = provider,
                sttLanguage = "fr",
                sonioxLanguages = "en, bn"
            )
            val controller = controller(stt, settings = settings)

            controller.start()
            runCurrent()
            controller.stop()
            advanceTimeBy(101)

            assertEquals(DictationState.Done("Hi"), controller.state.value)
            assertEquals(expected, stt.language)
        }
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
        val settings = openAi.copy(sttTimeoutSeconds = 20)
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
        val settings = openAi
            .copy(cleanupEnabled = true, cleanupProviderId = Settings.CLEANUP_PROVIDER_OPENROUTER)
        val controller = controller(FakeStt { "Hi" }, settings = settings)

        controller.start()
        runCurrent()
        assertEquals(1, warmUps)
        controller.stop()
        assertEquals(2, warmUps)
        advanceTimeBy(101)
        assertEquals(DictationState.Done("Hi"), controller.state.value)
        assertEquals(2, warmUps)
        // Both with the session's settings (VAD off: no model passed), so the same provider is warmed.
        assertEquals(List(2) { settings.copy(vadEnabled = false) }, warmUpSettings)
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

    private fun speed(percent: Int) = openAi.copy(sttSpeedPercent = percent)

    /** Speech at frames 20..29 and 200..209, stopped at frame 240 (before the second segment's hangover ends). */
    private fun TestScope.twoSegments(stt: FakeStt, settings: Settings = speed(100)) {
        val controller = controller(stt, audio = frames, settings = settings, vadModel = speechAt(20..29, 200..209))
        controller.start()
        runCurrent()
        advanceTimeBy(240 * 32L + 10)
        controller.stop()
        advanceTimeBy(32)
        assertEquals(DictationState.Done("Hi"), controller.state.value)
    }

    @Test
    fun optedInProviderGetsOneEmptyArrayBetweenSegments() = runTest {
        val stt = FakeStt(rate = 16_000, streams = false, live = true, wantsSegmentEnds = true) { "Hi" }
        twoSegments(stt)

        val ends = stt.received.indices.filter { stt.received[it].isEmpty() }
        // Segment 1 is 6..81 (pre-roll, speech, 52 hangover frames); frame 82 ends it and joins the pre-roll.
        assertEquals(listOf(76), ends)
        assertEquals((6..81).toList() + (186..241).toList(), tags(stt.received.filter { it.isNotEmpty() }))
    }

    @Test
    fun segmentEndSurvivesTheSpeedUp() = runTest {
        val stt = FakeStt(rate = 16_000, streams = false, live = true, wantsSegmentEnds = true) { "Hi" }
        twoSegments(stt, speed(150))

        val ends = stt.received.indices.filter { stt.received[it].isEmpty() }
        assertEquals(1, ends.size)
        val before = stt.received.subList(0, ends[0]).sumOf { it.size }
        assertEquals(76 * 1024 / 1.5, before.toDouble(), 76 * 1024 * 0.02)
    }

    @Test
    fun providerThatDidNotOptInNeverGetsAnEmptyArray() = runTest {
        val stt = FakeStt(rate = 16_000) { "Hi" }
        twoSegments(stt)

        assertTrue(stt.received.isNotEmpty())
        assertTrue(stt.received.none { it.isEmpty() })
    }

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
    fun speed200IsIgnoredWhenTheProviderDisallowsIt() = runTest {
        val stt = FakeStt(rate = 16_000, allowsSpeedUp = false) { "Hi" }
        val recorded = mutableListOf<ByteArray>()
        val controller = controller(
            stt,
            audio = frames.map { it.also { bytes -> recorded += bytes } },
            settings = speed(200),
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

    /** [frames] at 16 kHz (32 ms each): frames 0..100 before the stop at 3,210 ms, frame 101 ends the audio. */
    private fun TestScope.dictateFrames(controller: DictationController, afterStopMs: Long = 32) {
        controller.start()
        runCurrent()
        advanceTimeBy(100 * 32L + 10)
        controller.stop()
        advanceTimeBy(afterStopMs)
    }

    @Test
    fun sessionIsRecordedAfterTheHookWithItsTextAndTimes() = runTest {
        val stt = FakeStt(rate = 16_000) {
            delay(300)
            "Hello world"
        }
        val controller = controller(stt, audio = frames, onFinalTranscript = {
            events += "hook"
            delay(200)
            DictationState.Done("Hello, world.")
        })

        dictateFrames(controller, afterStopMs = 32 + 300 + 200)

        assertEquals(DictationState.Done("Hello, world."), controller.state.value)
        assertEquals(listOf("hook", "record"), events)
        // 102 frames recorded and sent; stop at 3,210 ms, audio ends at 3,232, STT returns 300 ms later.
        assertEquals(DictationRecord(3_264, 3_264, 322, 522, 2), records.single().second)
    }

    @Test
    fun transcriptBeforeTheStopIsNotRecordedEvenIfStopIsTappedDuringTheHook() = runTest {
        // Returns after 5 chunks while still listening, like the Gateway's early `finish`.
        val stt = object : SpeechToTextProvider {
            override fun sampleRate(model: String) = 16_000

            override suspend fun transcribe(
                audio: Flow<ByteArray>,
                model: String,
                language: String,
                timeoutMs: Long,
                onPartial: (String) -> Unit,
            ): String {
                audio.take(5).collect()
                return "Hi"
            }

            override fun warmUp() = Unit
        }
        val controller = controller(stt, audio = frames, onFinalTranscript = {
            delay(500)
            DictationState.Done(it)
        })

        controller.start()
        runCurrent()
        advanceTimeBy(5 * 32L + 100) // The transcript is in; the hook runs, the state is still Listening.
        controller.stop()
        advanceTimeBy(1_000)

        assertEquals(DictationState.Done("Hi"), controller.state.value)
        assertTrue(records.isEmpty())
    }

    @Test
    fun skipSilenceBillsOnlyTheSentAudio() = runTest {
        val controller = controller(FakeStt(rate = 16_000) { "Hi" }, audio = frames, vadModel = speechAt(20..29))

        dictateFrames(controller)

        // Frames 6..81 sent (pre-roll, speech, hangover).
        val record = records.single().second
        assertEquals(3_264L, record.recordedMs)
        assertEquals(76 * 32L, record.billedMs)
        assertEquals(22L, record.waitMs)
        assertEquals(22L, record.doneMs)
    }

    @Test
    fun speedDividesTheBilledAudio() = runTest {
        val controller = controller(
            FakeStt(rate = 16_000) { "Hi" },
            audio = frames,
            settings = speed(150),
            vadModel = speechAt(20..29)
        )

        dictateFrames(controller)

        assertEquals(76 * 32L * 100 / 150, records.single().second.billedMs)
    }

    @Test
    fun providerBilledByStreamTimeBillsTheWholeRecording() = runTest {
        val controller = controller(
            FakeStt(rate = 16_000, allowsSpeedUp = false) { "Hi" },
            audio = frames,
            settings = speed(200),
            vadModel = speechAt(20..29)
        )

        dictateFrames(controller)

        val record = records.single().second
        assertEquals(3_264L, record.billedMs)
        assertEquals(record.recordedMs, record.billedMs)
    }

    @Test
    fun onDeviceSessionIsRecordedToo() = runTest {
        val settings = openAi.copy(sttProviderId = Settings.STT_PROVIDER_ON_DEVICE)
        val controller = controller(FakeStt(rate = 16_000) { "Hi" }, audio = frames, settings = settings)

        dictateFrames(controller)

        assertTrue(records.single().first.sttOnDevice)
    }

    @Test
    fun sessionsWithoutInsertedTextAreNotRecorded() = runTest {
        dictateFrames(controller(FakeStt(rate = 16_000) { "  " }, audio = frames))
        dictateFrames(controller(FakeStt(rate = 16_000) { throw SttException("OpenAI error: Boom") }, audio = frames))
        dictateFrames(controller(FakeStt(rate = 16_000) { "Hi" }, audio = frames, vadModel = { 0f }))
        dictateFrames(
            controller(FakeStt(rate = 16_000) { "Hi" }, audio = frames, onFinalTranscript = { error("Hook failed") })
        )
        val cancelled = controller(FakeStt(rate = 16_000) { "Hi" }, audio = frames)
        cancelled.start()
        runCurrent()
        advanceTimeBy(500)
        cancelled.cancel()
        advanceTimeBy(500)

        assertTrue(records.isEmpty())
    }

    /** Starts [controller], stops it after the first chunk and lets the chunk recorded during the stop end it. */
    private fun TestScope.dictate(controller: DictationController) {
        controller.start()
        runCurrent()
        controller.stop()
        advanceTimeBy(101)
    }

    @Test
    fun fillersAreRemovedBeforeTheFinalTranscriptHook() = runTest {
        val controller = controller(FakeStt { "Uh, so I think, hmm, it works" })
        dictate(controller)

        assertEquals(listOf("So I think, it works"), finals)
        assertEquals(DictationState.Done("So I think, it works"), controller.state.value)
        assertEquals(5, records.single().second.words)
    }

    @Test
    fun onlyFillersEndWithNoSpeechAndRecordNothing() = runTest {
        val controller = controller(FakeStt { "Uh. Hmm." })
        dictate(controller)

        assertEquals(DictationState.Error("No speech detected"), controller.state.value)
        assertTrue(finals.isEmpty())
        assertTrue(records.isEmpty())
    }

    @Test
    fun fillerRemovalOffPassesTheTranscriptUnchanged() = runTest {
        val detections = mutableListOf<String>()
        val settings = openAi.copy(removeFillerWords = false)
        val controller = controller(
            FakeStt { "Uh, um, hello" },
            settings = settings,
            detectLanguage = { detections += it; "en" }
        )
        dictate(controller)

        assertEquals(listOf("Uh, um, hello"), finals)
        assertTrue(detections.isEmpty())
    }

    @Test
    fun knownLanguageSkipsTheDetector() = runTest {
        val detections = mutableListOf<String>()
        val settings = openAi.copy(sttLanguage = "en")
        val controller = controller(
            FakeStt { "um hello there" },
            settings = settings,
            detectLanguage = { detections += it; null }
        )
        dictate(controller)

        assertEquals(listOf("hello there"), finals)
        assertTrue(detections.isEmpty())
    }

    @Test
    fun unknownLanguageAsksTheDetectorWithTheRawText() = runTest {
        val detections = mutableListOf<String>()
        dictate(controller(FakeStt { "um hello there" }, detectLanguage = { detections += it; "en" }))
        dictate(controller(FakeStt { "eu vi um carro" }, detectLanguage = { detections += it; "pt" }))

        assertEquals(listOf("hello there", "eu vi um carro"), finals)
        assertEquals(listOf("um hello there", "eu vi um carro"), detections)
    }

    @Test
    fun detectorIsSkippedWithoutAGatedFiller() = runTest {
        val detections = mutableListOf<String>()
        dictate(controller(FakeStt { "uh hello" }, detectLanguage = { detections += it; "en" }))

        assertEquals(listOf("hello"), finals)
        assertTrue(detections.isEmpty())
    }

    @Test
    fun slowDetectorTimesOutToUnknown() = runTest {
        val controller = controller(
            FakeStt { "um hello" },
            detectLanguage = {
                delay(5_000)
                "en"
            }
        )
        controller.start()
        runCurrent()
        controller.stop()
        advanceTimeBy(100 + LANGUAGE_DETECTION_TIMEOUT_MS)
        assertTrue(controller.state.value is DictationState.Finalizing)

        advanceTimeBy(1)
        assertEquals(DictationState.Done("um hello"), controller.state.value)
        assertEquals(listOf("um hello"), finals)
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
