/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.dictation

import android.util.Log
import dev.jamius.iris.audio.AudioCaptureException
import dev.jamius.iris.data.settings.Settings
import dev.jamius.iris.stt.SpeechToTextProvider
import dev.jamius.iris.stt.SttException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface DictationState {
    data object Idle : DictationState

    /** Recording and streaming; [text] is the live transcript so far. */
    data class Listening(val text: String) : DictationState

    /** Recording stopped; waiting for the final transcript. */
    data class Finalizing(val text: String) : DictationState

    /** Shown briefly, then back to [Idle]. */
    data class Done(val text: String) : DictationState

    /** Shown for a few seconds, then back to [Idle]. [message] is user-readable. */
    data class Error(val message: String) : DictationState
}

internal const val DONE_DISPLAY_MS = 1_500L
internal const val ERROR_DISPLAY_MS = 3_000L

/**
 * Runs one dictation session at a time: Idle -> Listening -> Finalizing -> Done/Error -> Idle.
 * One instance per accessibility service; use on the main thread.
 *
 * @param audio cold flow; each collection records a new session's audio.
 * @param onFinalTranscript called once per successful session with the final text, before Done. It runs inside
 *   the session (still Finalizing): cancelling the session cancels it, and its exceptions end in Error.
 */
class DictationController(
    private val scope: CoroutineScope,
    private val audio: Flow<ByteArray>,
    private val stt: SpeechToTextProvider,
    private val settings: Flow<Settings>,
    private val onFinalTranscript: suspend (String) -> Unit,
) {
    private val _state = MutableStateFlow<DictationState>(DictationState.Idle)
    val state: StateFlow<DictationState> = _state.asStateFlow()

    private var session: Job? = null
    private var stopRequested = false

    /**
     * Bubble tap: starts a session (also while a result is shown), stops one that is listening, and cancels one
     * that is finalizing (the escape if finalizing stalls).
     */
    fun onTap() {
        when (val state = _state.value) {
            is DictationState.Listening -> {
                stopRequested = true
                _state.value = DictationState.Finalizing(state.text)
            }
            is DictationState.Finalizing -> cancel()
            else -> start()
        }
    }

    /** Cancels any session, discarding its result, and returns to Idle. */
    fun cancel() {
        session?.cancel()
        session = null
        _state.value = DictationState.Idle
    }

    private fun start() {
        session?.cancel()
        stopRequested = false
        _state.value = DictationState.Listening("")
        session = scope.launch {
            val result = try {
                val config = settings.first()
                // Ends the audio after the chunk during which stop was tapped, so speech up to the tap is kept.
                val chunks = audio.transformWhile {
                    emit(it)
                    !stopRequested
                }
                val text = stt.transcribe(chunks, config.sttModel, config.sttLanguage, ::onPartial)
                if (text.isBlank()) {
                    DictationState.Error("No speech detected")
                } else {
                    onFinalTranscript(text)
                    DictationState.Done(text)
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
            delay(if (result is DictationState.Done) DONE_DISPLAY_MS else ERROR_DISPLAY_MS)
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

    private companion object {
        const val TAG = "DictationController"
        const val GENERIC_ERROR = "Something went wrong"
    }
}
