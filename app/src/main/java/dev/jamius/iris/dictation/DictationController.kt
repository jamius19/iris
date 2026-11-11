/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.dictation

import android.os.SystemClock
import android.util.Log
import dev.jamius.iris.audio.AudioCaptureException
import dev.jamius.iris.audio.AudioChunk
import dev.jamius.iris.data.settings.Settings
import dev.jamius.iris.stt.SpeechToTextProvider
import dev.jamius.iris.stt.SttException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
internal const val ERROR_DISPLAY_MS = 3_000L

/** After stop, taps are ignored this long while the transcript isn't final (e.g. a double tap on stop). */
internal const val FINALIZE_TAP_GRACE_MS = 5_000L

/**
 * Runs one dictation session at a time: Idle -> Listening -> Finalizing -> Done/Error -> Idle.
 * One instance per accessibility service; use on the main thread.
 *
 * @param audio cold flow; each collection records a new session's audio.
 * @param clock the [AudioChunk.readAtNanos] time base.
 * @param onFinalTranscript called with the final text of each successful session; returns the
 *   [DictationState.Done] to show. It runs inside the session (still Finalizing): cancelling the session cancels
 *   it, and its exceptions end in Error. A tap while it runs cancels it and calls it again with
 *   `skipCleanup = true`.
 * @param warmUpCleanup if cleanup is enabled, called as recording starts and again on stop, so the cleanup call
 *   finds a warm connection. Must return at once.
 */
class DictationController(
    private val scope: CoroutineScope,
    private val audio: Flow<AudioChunk>,
    private val stt: SpeechToTextProvider,
    private val settings: Flow<Settings>,
    private val warmUpCleanup: () -> Unit,
    private val onFinalTranscript: suspend (transcript: String, skipCleanup: Boolean) -> DictationState.Done,
    private val clock: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    private val _state = MutableStateFlow<DictationState>(DictationState.Idle)
    val state: StateFlow<DictationState> = _state.asStateFlow()

    private var session: Job? = null

    /** [clock] time of the stop tap, or null while listening. */
    private var stoppedAt: Long? = null

    /** The session's settings have cleanup on: warm the cleanup connection on stop too. */
    private var cleanupEnabled = false

    /** The first [onFinalTranscript] call of the session, once the transcript is final. */
    private var hook: Job? = null

    /** Started on stop; once complete, a tap before the transcript is final cancels the session. */
    private var tapGrace: Job? = null

    /**
     * Bubble tap: starts a session (also while a result is shown) and stops one that is listening. While
     * finalizing: before the transcript is final, ignored for [FINALIZE_TAP_GRACE_MS] after stop, then cancels the
     * session (the escape if it stalls); after, skips cleanup so the raw transcript is inserted right away.
     */
    fun onTap() {
        when (val state = _state.value) {
            is DictationState.Listening -> {
                stoppedAt = clock()
                // A long dictation may outlive the idle connection (pool/server/NAT); runs alongside STT finalization.
                if (cleanupEnabled) warmUpCleanup()
                tapGrace = scope.launch { delay(FINALIZE_TAP_GRACE_MS) }
                _state.value = DictationState.Finalizing(state.text)
            }
            is DictationState.Finalizing -> {
                val hook = hook
                if (hook != null) hook.cancel() else if (tapGrace?.isCompleted == true) cancel()
            }
            else -> start()
        }
    }

    /** The field lost focus: discards a session that is still listening. One already stopped runs to the end. */
    fun onFocusLost() {
        if (_state.value is DictationState.Listening) cancel()
    }

    /** Cancels any session, discarding its result, and returns to Idle. */
    fun cancel() {
        session?.cancel()
        session = null
        hook = null
        _state.value = DictationState.Idle
    }

    private fun start() {
        session?.cancel()
        stoppedAt = null
        cleanupEnabled = false
        hook = null
        _state.value = DictationState.Listening("")
        session = scope.launch {
            val result = try {
                val config = settings.first()
                cleanupEnabled = config.cleanupEnabled
                if (cleanupEnabled) warmUpCleanup()
                val text = coroutineScope {
                    val sessionScope = this
                    // Recording runs in its own coroutine, so the audio ends (and STT commits) without waiting for
                    // the mic's next read and its release; the scope still waits for both before returning. It
                    // starts on the provider's first collect, after its API key check, so a missing key never
                    // opens the mic.
                    val recording = flow { emitAll(audio.produceIn(sessionScope)) }
                    // Sends every chunk read before the stop tap (even if queued behind a stalled main thread), then
                    // the one during which stop was tapped, so speech up to the tap is kept.
                    val chunks = recording.transformWhile {
                        emit(it.bytes)
                        stoppedAt.let { stop -> stop == null || it.readAtNanos <= stop }
                    }
                    stt.transcribe(chunks, config.sttModel, config.sttLanguage, ::onPartial)
                }
                if (text.isBlank()) {
                    DictationState.Error("No speech detected")
                } else {
                    finish(text)
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
            _state.value = result
            val brief = result is DictationState.Done && result.notice == null
            delay(if (brief) DONE_DISPLAY_MS else ERROR_DISPLAY_MS)
            _state.value = DictationState.Idle
        }
    }

    /** Runs [onFinalTranscript]; if a tap cancels it (see [onTap]), runs it again with `skipCleanup = true`. */
    private suspend fun finish(text: String): DictationState.Done = coroutineScope {
        val first = async { onFinalTranscript(text, false) }
        hook = first
        try {
            first.await()
        } catch (e: CancellationException) {
            ensureActive() // Rethrows if the session itself was cancelled.
            onFinalTranscript(text, true)
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

    private companion object {
        const val TAG = "DictationController"
        const val GENERIC_ERROR = "Something went wrong"
    }
}
