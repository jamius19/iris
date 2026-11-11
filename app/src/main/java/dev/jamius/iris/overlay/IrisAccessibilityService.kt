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

    /** The final hook is running cleanup: [render] shows "Cleaning up…" for Finalizing. */
    private var cleaningUp = false

    override fun onServiceConnected() {
        val container = (application as IrisApp).container
        val repository = container.settingsRepository
        val dictation = container.dictationController(scope, ::onFinalTranscript)
        this.dictation = dictation
        val overlay = BubbleOverlay(
            context = this,
            onTap = {
                if (dictation.state.value is DictationState.Listening) target = focusedEditable
                dictation.onTap()
            },
            onMoved = { side, yFraction -> scope.launch { repository.setBubblePosition(side, yFraction) } }
        )
        scope.launch { dictation.state.collect(::render) }
        scope.launch {
            val settings = repository.settings.first()
            // Only now, so nothing shows the bubble at the default position before the stored one loads.
            this@IrisAccessibilityService.overlay = overlay
            overlay.setImeTop(imeTop())
            overlay.setPosition(settings.bubbleSide, settings.bubbleYFraction)
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
        } else if (!handler.hasCallbacks(hideBubble)) {
            handler.postDelayed(hideBubble, HIDE_DELAY_MS)
        }
    }

    private fun render(state: DictationState) {
        val overlay = overlay ?: return
        // Finalizing stops the pulse while keeping the text, so it reads as "working on it".
        overlay.setListening(state is DictationState.Listening)
        when (state) {
            DictationState.Idle -> overlay.hideText()
            is DictationState.Listening -> overlay.showText(state.text.ifEmpty { "Listening…" })
            is DictationState.Finalizing -> overlay.showText(
                if (cleaningUp) "Cleaning up…" else state.text.ifEmpty { "Finishing…" }
            )
            is DictationState.Done -> overlay.showText(state.notice ?: state.text, tail = false)
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
        var text = transcript
        var cleanupError: String? = null
        var targetFocused = false
        val insertion = try {
            val container = (application as IrisApp).container
            val settings = container.settingsRepository.settings.first()
            if (settings.cleanupEnabled && !skipCleanup) {
                // State stays Finalizing (nothing re-renders on its own) until this returns, so the status stays up.
                cleaningUp = true
                dictation?.let { render(it.state.value) }
                val prompt = settings.activePrompt ?: Settings.DEFAULT_PROMPT
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
                    cleaningUp = false
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
