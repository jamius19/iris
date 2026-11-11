/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.overlay

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.jamius.iris.IrisApp
import dev.jamius.iris.cleanup.CleanupException
import dev.jamius.iris.data.settings.Settings
import dev.jamius.iris.dictation.DictationController
import dev.jamius.iris.dictation.DictationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Shows the bubble overlay while an editable, non-password field in another app has input focus.
 * Focus is re-checked with `findFocus(FOCUS_INPUT)` on every focus/window event (see accessibility_service_config).
 */
class IrisAccessibilityService : AccessibilityService() {

    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: BubbleOverlay? = null
    private var dictation: DictationController? = null

    // Debounced so transient focus gaps (rotation, app transitions, WebViews) don't flicker the bubble.
    // Losing focus discards a session that is still listening; a stopped one finishes (copied to the clipboard).
    private val hideBubble = Runnable {
        dictation?.onFocusLost()
        overlay?.hide()
    }

    /** The focused editable field the bubble is shown for. Call `refresh()` before acting on it. */
    var focusedEditable: AccessibilityNodeInfo? = null
        private set

    /** [focusedEditable] when the user tapped stop: the field the dictation is inserted into. */
    private var target: AccessibilityNodeInfo? = null

    /** Cached from settings: STT warm-ups are skipped without a key (no decryption per accessibility event). */
    private var openAiKeySet = false

    /** `elapsedRealtime` of the stop tap and of the final transcript, for the timing log. */
    private var stoppedAt = 0L
    private var transcriptAt = 0L

    override fun onServiceConnected() {
        val container = (application as IrisApp).container
        val repository = container.settingsRepository
        val dictation = container.dictationController(scope, ::onFinalTranscript)
        this.dictation = dictation
        val overlay = BubbleOverlay(
            context = this,
            onTap = {
                if (dictation.state.value is DictationState.Listening) {
                    target = focusedEditable
                    stoppedAt = SystemClock.elapsedRealtime()
                }
                dictation.onTap()
            },
            onMoved = { side, yFraction -> scope.launch { repository.setBubblePosition(side, yFraction) } }
        )
        scope.launch {
            repository.settings.collect {
                openAiKeySet = it.openAiKeySet
                overlay.setPanelSize(it.panelLines, it.panelWidthPercent) // Skips unchanged values.
            }
        }
        scope.launch {
            dictation.state.collect {
                render(it)
                warmUpStt() // A finished session used up the warmed connection; warm one for the next.
            }
        }
        scope.launch {
            val settings = repository.settings.first()
            // Only now, so nothing shows the bubble at the default position before the stored one loads.
            this@IrisAccessibilityService.overlay = overlay
            overlay.setImeTop(imeTop())
            overlay.setPosition(settings.bubbleSide, settings.bubbleYFraction)
            overlay.setPanelSize(settings.panelLines, settings.panelWidthPercent)
            refreshFocus() // After the stored position loads, so the bubble doesn't jump.
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            refreshFocus()
            return
        }
        // Skips Iris's own overlay windows (e.g. the panel resizing as text streams in) to avoid needless IPC.
        if (overlay?.ownsWindow(event.windowId) == true) return
        val changes = event.windowChanges
        if (changes and IME_CHANGES != 0) overlay?.setImeTop(imeTop())
        if (changes and FOCUS_CHANGES != 0) refreshFocus()
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged()
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideBubble)
        overlay?.hide()
        overlay = null
        dictation = null
        focusedEditable = null
        target = null
        scope.cancel() // Also cancels any dictation session, which releases the mic and closes the socket.
        super.onDestroy()
    }

    private fun refreshFocus() {
        val overlay = overlay ?: return
        // Iris's own fields (API keys) are skipped; so is any password field.
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { it.isEditable && !it.isPassword && it.packageName?.toString() != packageName }
        focusedEditable = node
        if (node != null) {
            handler.removeCallbacks(hideBubble)
            overlay.show()
            dictation?.let { render(it.state.value) } // Restores the panel if the bubble was hidden mid-session.
            warmUpStt()
        } else if (!handler.hasCallbacks(hideBubble)) {
            handler.postDelayed(hideBubble, HIDE_DELAY_MS)
        }
    }

    /** Pre-warms the STT connection while the bubble is up and no session runs; the provider throttles repeats. */
    private fun warmUpStt() {
        val state = dictation?.state?.value ?: return
        if (!openAiKeySet || focusedEditable == null) return
        if (state is DictationState.Listening || state is DictationState.Finalizing) return
        (application as IrisApp).container.speechToText.warmUp()
    }

    private fun render(state: DictationState) {
        val overlay = overlay ?: return
        // Done with a notice (cleanup failed, or only copied to the clipboard) counts as a failure.
        overlay.setStatus(
            when (state) {
                DictationState.Idle -> BubbleStatus.IDLE
                is DictationState.Listening -> BubbleStatus.LISTENING
                is DictationState.Finalizing -> BubbleStatus.WORKING
                is DictationState.Done -> if (state.notice == null) BubbleStatus.SUCCESS else BubbleStatus.FAILURE
                is DictationState.Error -> BubbleStatus.FAILURE
            }
        )
        when (state) {
            DictationState.Idle -> overlay.hideText()
            is DictationState.Listening -> overlay.showText(state.text.ifEmpty { "Listening…" })
            is DictationState.Finalizing -> overlay.showText(state.text.ifEmpty { "Finishing…" })
            // Inserted: the tick says it all. A notice explains the failure.
            is DictationState.Done -> if (state.notice == null) {
                overlay.hideText()
            } else {
                overlay.showText(state.notice, tail = false)
            }
            is DictationState.Error -> overlay.showText(state.message, tail = false)
        }
    }

    /**
     * Called with the final transcript of each successful session, inside the session (cancelled with it). Runs the
     * optional LLM cleanup (unless [skipCleanup]), then inserts at the cursor of [target] if it still has focus,
     * else copies to the clipboard. A failed cleanup inserts the original transcript, and any other failure copies
     * the text, so the dictation is never lost.
     */
    private suspend fun onFinalTranscript(transcript: String, skipCleanup: Boolean): DictationState.Done {
        if (!skipCleanup) transcriptAt = SystemClock.elapsedRealtime() // The retry after a skip tap keeps it.
        var text = transcript
        var cleanupError: String? = null
        var cleanupMs: Long? = null
        var targetFocused = false
        val insertion = try {
            val container = (application as IrisApp).container
            val settings = container.settingsRepository.settings.first()
            if (settings.cleanupEnabled && !skipCleanup) {
                val prompt = settings.activePrompt ?: Settings.DEFAULT_PROMPT
                val cleanupStart = SystemClock.elapsedRealtime()
                try {
                    text = container.cleanup.cleanup(transcript, prompt.systemPrompt, settings.cleanupModel)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: CleanupException) {
                    cleanupError = e.message
                } catch (e: Exception) {
                    Log.e(TAG, "Cleanup failed", e)
                    cleanupError = "Cleanup failed"
                } finally {
                    cleanupMs = SystemClock.elapsedRealtime() - cleanupStart
                }
            }
            val target = target
            targetFocused = target != null && target == focusedEditable
            if (targetFocused) {
                insertText(this, target, text)
            } else {
                copyToClipboard(this, text)
                Insertion.COPIED
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Delivering the dictation failed", e)
            copyToClipboard(this, text)
            null
        }
        val cleanupTiming = cleanupMs?.let { "$it ms" + if (cleanupError != null) " failed" else "" }
            ?: if (skipCleanup) "skipped" else "off"
        Log.d(
            TAG,
            "Timing: stop->transcript ${transcriptAt - stoppedAt} ms, cleanup $cleanupTiming, " +
                "stop->done ${SystemClock.elapsedRealtime() - stoppedAt} ms"
        )
        return DictationState.Done(text, doneNotice(cleanupError, targetFocused, insertion))
    }

    private fun imeTop(): Int? = windows
        .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        ?.let { window -> Rect().also(window::getBoundsInScreen).top }

    private companion object {
        const val TAG = "IrisAccessibilityService"
        const val HIDE_DELAY_MS = 300L
        const val IME_CHANGES = AccessibilityEvent.WINDOWS_CHANGE_ADDED or
            AccessibilityEvent.WINDOWS_CHANGE_REMOVED or
            AccessibilityEvent.WINDOWS_CHANGE_BOUNDS
        const val FOCUS_CHANGES = AccessibilityEvent.WINDOWS_CHANGE_ADDED or
            AccessibilityEvent.WINDOWS_CHANGE_REMOVED or
            AccessibilityEvent.WINDOWS_CHANGE_FOCUSED or
            AccessibilityEvent.WINDOWS_CHANGE_ACTIVE
    }
}
