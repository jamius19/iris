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
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.jamius.iris.IrisApp
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

    // Debounced so transient focus gaps (rotation, app transitions, WebViews) don't flicker the bubble.
    // Listening state is deliberately untouched here: CP3's dictation controller decides what happens to an
    // active session when focus is lost (hook it in this runnable).
    private val hideBubble = Runnable { overlay?.hide() }

    /** The focused editable field the bubble is shown for. Call `refresh()` before acting on it. */
    var focusedEditable: AccessibilityNodeInfo? = null
        private set

    override fun onServiceConnected() {
        val repository = (application as IrisApp).container.settingsRepository
        val overlay = BubbleOverlay(
            context = this,
            onTap = ::toggleListening,
            onMoved = { side, yFraction -> scope.launch { repository.setBubblePosition(side, yFraction) } }
        )
        this.overlay = overlay
        overlay.setImeTop(imeTop())
        scope.launch {
            val settings = repository.settings.first()
            overlay.setPosition(settings.bubbleSide, settings.bubbleYFraction)
            refreshFocus() // After the stored position loads, so the bubble doesn't jump.
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            refreshFocus()
            return
        }
        // Skips changes Iris's own overlay causes (e.g. layout updates) and avoids needless IPC.
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
        focusedEditable = null
        scope.cancel()
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
        } else if (!handler.hasCallbacks(hideBubble)) {
            handler.postDelayed(hideBubble, HIDE_DELAY_MS)
        }
    }

    // CP2 placeholder: a tap only toggles the visual state. CP3's dictation controller replaces this.
    private fun toggleListening() {
        val overlay = overlay ?: return
        overlay.setListening(!overlay.isListening)
        if (overlay.isListening) overlay.showText("Listening…") else overlay.hideText()
    }

    private fun imeTop(): Int? = windows
        .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        ?.let { window -> Rect().also(window::getBoundsInScreen).top }

    private companion object {
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
