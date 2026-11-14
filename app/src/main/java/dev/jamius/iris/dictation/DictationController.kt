/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.dictation

import android.os.SystemClock
import android.util.Log
import dev.jamius.iris.audio.AudioCaptureException
import dev.jamius.iris.audio.AudioChunk
import dev.jamius.iris.audio.SpeechGate
import dev.jamius.iris.audio.timeStretched
import dev.jamius.iris.data.settings.Settings
import dev.jamius.iris.stt.SpeechToTextProvider
import dev.jamius.iris.stt.SttException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

sealed interface DictationState {
    data object Idle : DictationState

    /** Recording and streaming; [text] is the live transcript so far. */
    data class Listening(val text: String) : DictationState

    /** Recording stopped; waiting for the final transcript. */
    data class Finalizing(val text: String) : DictationState

    /**
     * Shown briefly, then back to [Idle]. [text] is what was inserted; [notice], if set, is shown instead and for
     * longer (e.g. cleanup failed, or the text was only copied to the clipboard).
     */
    data class Done(val text: String, val notice: String? = null) : DictationState

    /** Shown for a few seconds, then back to [Idle]. [message] is user-readable. */
    data class Error(val message: String) : DictationState
}

internal const val DONE_DISPLAY_MS = 1_500L
internal const val ERROR_DISPLAY_MS = 2 * DONE_DISPLAY_MS

/** Text language detection (filler words) can't hold up Finalizing longer than this (then: unknown). */
internal const val LANGUAGE_DETECTION_TIMEOUT_MS = 1_000L

/**
 * Runs one dictation session at a time: Idle -> Listening -> Finalizing -> Done/Error -> Idle.
 * One instance per accessibility service; use on the main thread. [start] begins a session (also while a result
 * is shown), [stop] ends the recording of a listening one, [cancel] discards any; a finalizing session can't be
 * stopped or restarted, it ends through the providers' own timeouts.
 *
 * @param audio cold flow at the given sample rate (Hz); each collection records a new session's audio.
 * @param stt the speech-to-text provider for a session's settings; picked once per session.
 * @param clock the [AudioChunk.readAtNanos] time base.
 * @param onFinalTranscript called with the final text of each successful session; returns the
 *   [DictationState.Done] to show. It runs inside the session (still Finalizing): cancelling the session cancels
 *   it, and its exceptions end in Error.
 * @param warmUpCleanup called with the session's settings as recording starts and again on stop, if cleanup is
 *   enabled, so the cleanup call finds a warm connection. Must return at once.
 * @param vadModel a new Silero stream (16 kHz frame -> speech probability) for each session with VAD on; it is only
 *   called on [vadContext].
 * @param vadContext where the VAD gate runs (model load and inference: CPU work, off the main thread).
 * @param record called once per session whose [onFinalTranscript] returned (stopped, non-blank text), with the
 *   session's settings and its measurements; must return at once. Not for a transcript that arrived before the stop.
 * @param detectLanguage the transcript's language (ISO 639-1) detected from its text, or null if unsure; only called
 *   when filler removal is on, the settings don't give the language and a language-gated filler is in the text; must
 *   not throw. Bounded by [LANGUAGE_DETECTION_TIMEOUT_MS].
 */
class DictationController(
    private val scope: CoroutineScope,
    private val audio: (sampleRate: Int) -> Flow<AudioChunk>,
    private val stt: (Settings) -> SpeechToTextProvider,
    private val settings: Flow<Settings>,
    private val warmUpCleanup: (Settings) -> Unit,
    private val onFinalTranscript: suspend (transcript: String) -> DictationState.Done,
    private val vadModel: () -> (FloatArray) -> Float,
    private val vadContext: CoroutineContext = Dispatchers.Default,
    private val clock: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val record: (Settings, DictationRecord) -> Unit = { _, _ -> },
    private val detectLanguage: suspend (text: String) -> String? = { null },
) {
    private val _state = MutableStateFlow<DictationState>(DictationState.Idle)
    val state: StateFlow<DictationState> = _state.asStateFlow()

    private val _level = MutableStateFlow(0f)

    /** Loudness (0..1) of the latest chunk while listening; 0 otherwise. */
    val level: StateFlow<Float> = _level.asStateFlow()

    private var session: Job? = null

    /** [clock] time of the stop tap, or null while listening. */
    private var stoppedAt: Long? = null

    /** The session's settings if cleanup is on: the cleanup connection is warmed again on stop. */
    private var cleanupSettings: Settings? = null

    /** Stops recording a listening session, which then finalizes. Ignored in any other state. */
    fun stop() {
        val state = _state.value as? DictationState.Listening ?: return
        stoppedAt = clock()
        // A long dictation may outlive the idle connection (pool/server/NAT); runs alongside STT finalization.
        cleanupSettings?.let(warmUpCleanup)
        _level.value = 0f
        _state.value = DictationState.Finalizing(state.text)
    }

    /** The field lost focus: discards a session that is still listening. One already stopped runs to the end. */
    fun onFocusLost() {
        if (_state.value is DictationState.Listening) cancel()
    }

    /** Cancels any session, discarding its result, and returns to Idle. */
    fun cancel() {
        session?.cancel()
        session = null
        _level.value = 0f
        _state.value = DictationState.Idle
    }

    /** Starts a new session, also while a result is shown. Ignored while one is listening or finalizing. */
    fun start() {
        if (_state.value is DictationState.Listening || _state.value is DictationState.Finalizing) return
        session?.cancel()
        stoppedAt = null
        cleanupSettings = null
        _level.value = 0f
        _state.value = DictationState.Listening("")
        session = scope.launch {
            val result = try {
                val config = settings.first()
                if (config.cleanupEnabled) {
                    cleanupSettings = config
                    warmUpCleanup(config)
                }
                val stt = stt(config)
                val model = config.activeSttModel
                val rate = stt.sampleRate(model)
                val hangoverMs =
                    if (stt.liveText(model)) SpeechGate.STREAMING_HANGOVER_MS else SpeechGate.OFFLINE_HANGOVER_MS
                val gate =
                    if (config.vadEnabled) SpeechGate(rate, hangoverMs, vadModel(), stt.wantsSegmentEnds) else null
                var recordedBytes = 0L
                var sentBytes = 0L
                // Keep-alive for a socket while the gate withholds audio (servers may end a stream that gets none).
                val keepAlive = gate != null && stt.streams
                var lastSentAt: Long? = null
                // Applied after the gate and the stop: only sent chunks are stretched, and the tail is flushed at
                // the stop (after a NO_SPEECH check, so a session without speech sends nothing).
                val asRecorded = config.sttSpeedPercent == Settings.DEFAULT_STT_SPEED_PERCENT || !stt.allowsSpeedUp
                val transcript = coroutineScope {
                    val sessionScope = this
                    // VAD inference is CPU work: off the main thread. Every chunk still comes through (level, stop
                    // check); the gate only decides which bytes ride along with it.
                    val gated = audio(rate).map { it to (gate?.process(it.bytes) ?: listOf(it.bytes)) }
                        .flowOn(vadContext)
                    // Recording runs in its own coroutine, so the audio ends (and STT commits) without waiting for
                    // the mic's next read and its release; the scope still waits for both before returning. It
                    // starts on the provider's first collect, after its API key check, so a missing key never
                    // opens the mic.
                    val recording = flow { emitAll(gated.produceIn(sessionScope)) }
                    // Sends every chunk read before the stop tap (even if queued behind a stalled main thread), then
                    // the one during which stop was tapped, so speech up to the tap is kept.
                    val chunks = recording.transformWhile { (chunk, send) ->
                        if (stoppedAt == null) _level.value = chunk.level
                        recordedBytes += chunk.bytes.size
                        send.forEach {
                            sentBytes += it.size
                            emit(it)
                        }
                        if (keepAlive) {
                            val last = lastSentAt
                            if (send.isNotEmpty() || last == null) {
                                lastSentAt = chunk.readAtNanos
                            } else if (chunk.readAtNanos - last >= KEEP_ALIVE_NANOS) {
                                // Zeros, not counted as sent: a session without speech still ends with NO_SPEECH.
                                emit(ByteArray(chunk.bytes.size))
                                lastSentAt = chunk.readAtNanos
                            }
                        }
                        stoppedAt.let { stop -> stop == null || chunk.readAtNanos <= stop }
                    }.onCompletion { cause ->
                        // Null only when the recording ended at the stop (not on errors or cancellation).
                        if (gate == null || cause != null) return@onCompletion
                        val summary = "VAD: sent ${ms(sentBytes, rate)} ms of ${ms(recordedBytes, rate)} ms"
                        gate.failure?.let { Log.w(TAG, "$summary (VAD failed: sent everything from then on)", it) }
                            ?: Log.d(TAG, summary)
                        // Nothing to transcribe: fail before the provider pads or commits any audio.
                        if (sentBytes == 0L) throw SttException(NO_SPEECH)
                    }
                    val sent = if (asRecorded) chunks else {
                        val speed = config.sttSpeedPercent / 100f
                        var inBytes = 0L
                        var outBytes = 0L
                        chunks.onEach { inBytes += it.size }
                            .timeStretched(rate, speed)
                            .onEach { outBytes += it.size }
                            .onCompletion { cause ->
                                if (cause != null) return@onCompletion
                                Log.d(TAG, "Speed ${speed}x: sent ${ms(inBytes, rate)} ms as ${ms(outBytes, rate)} ms")
                            }
                    }
                    stt.transcribe(
                        sent,
                        model,
                        config.activeSttLanguage,
                        config.sttTimeoutSeconds * 1000L,
                        ::onPartial
                    )
                }
                val transcribedAt = clock()
                // Read now: a transcript that arrives before the stop (the Gateway's early `finish`) has none, and a
                // stop tapped later, during the hook, must not count.
                val stop = stoppedAt
                val text = withoutFillers(transcript, config)
                if (text.isBlank()) {
                    DictationState.Error(NO_SPEECH)
                } else {
                    val done = onFinalTranscript(text)
                    if (stop != null) {
                        val recordedMs = ms(recordedBytes, rate)
                        val sentMs = ms(sentBytes, rate)
                        // Soniox real-time bills the whole stream; Speed shortens what the others get.
                        val billedMs = when {
                            !stt.allowsSpeedUp -> recordedMs
                            asRecorded -> sentMs
                            else -> sentMs * 100 / config.sttSpeedPercent
                        }
                        val waitMs = (transcribedAt - stop) / NANOS_PER_MS
                        val doneMs = (clock() - stop) / NANOS_PER_MS
                        record(config, DictationRecord(recordedMs, billedMs, waitMs, doneMs, wordCount(done.text)))
                    }
                    done
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is SttException || e is AudioCaptureException) {
                    DictationState.Error(e.message ?: GENERIC_ERROR)
                } else {
                    Log.e(TAG, "Dictation session failed", e)
                    DictationState.Error(GENERIC_ERROR)
                }
            }
            _level.value = 0f // E.g. an STT error while listening.
            _state.value = result
            val brief = result is DictationState.Done && result.notice == null
            delay(if (brief) DONE_DISPLAY_MS else ERROR_DISPLAY_MS)
            _state.value = DictationState.Idle
        }
    }

    private fun onPartial(text: String) {
        _state.update {
            when (it) {
                is DictationState.Listening -> DictationState.Listening(text)
                is DictationState.Finalizing -> DictationState.Finalizing(text)
                else -> it
            }
        }
    }

    /** [text] without filler words if the setting is on; the language from the settings, else detected from [text]. */
    private suspend fun withoutFillers(text: String, config: Settings): String {
        if (!config.removeFillerWords) return text
        val language = outputLanguage(config) ?: if (hasGatedFiller(text)) detectedLanguage(text) else null
        return removeFillerWords(text, language)
    }

    private suspend fun detectedLanguage(text: String): String? {
        val start = clock()
        val language = withTimeoutOrNull(LANGUAGE_DETECTION_TIMEOUT_MS) { detectLanguage(text) }
        val elapsedMs = (clock() - start) / NANOS_PER_MS
        Log.d(TAG, "Filler words: language ${language ?: "unknown"} (detected in $elapsedMs ms)")
        return language
    }

    /** Duration of [bytes] of PCM16 mono at [rate]. */
    private fun ms(bytes: Long, rate: Int): Long = bytes * 1000 / (rate * 2)

    private companion object {
        const val TAG = "DictationController"
        const val GENERIC_ERROR = "Something went wrong"
        const val NO_SPEECH = "No speech detected"

        /** A chunk of zeros goes to a streaming provider after this long without sent audio. */
        const val KEEP_ALIVE_NANOS = 5_000_000_000L
        const val NANOS_PER_MS = 1_000_000L
    }
}
