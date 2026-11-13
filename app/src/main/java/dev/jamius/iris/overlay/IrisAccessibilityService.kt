/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.overlay

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.Rect
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.jamius.iris.IrisApp
import dev.jamius.iris.cleanup.CleanupException
import dev.jamius.iris.cleanup.parseProviderOptions
import dev.jamius.iris.data.settings.Settings
import dev.jamius.iris.dictation.DictationController
import dev.jamius.iris.dictation.DictationState
import dev.jamius.iris.stt.SpeechToTextProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shows the bubble overlay while an editable, non-password field in another app has input focus, or the keyboard is
 * open for another app (some fields, e.g. ChatGPT's chat box, report no editable input focus).
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

    /** True while the bubble is wanted: an editable field has input focus, or the keyboard is open (see class doc). */
    private var fieldFocused = false

    /**
     * The input-focused node the bubble is shown for: editable, or (keyboard only) not editable or null. Call
     * `refresh()` before acting on it.
     */
    var focusedField: AccessibilityNodeInfo? = null
        private set

    /** [focusedField] when the user tapped stop: the field the dictation is inserted into. */
    private var target: AccessibilityNodeInfo? = null

    /**
     * Cached from settings: the selected STT provider, or null without its key, so warm-ups are skipped (no
     * decryption per accessibility event).
     */
    private var sttWarmUp: SpeechToTextProvider? = null

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
            // The ✕ cancels while listening; otherwise the button starts (a no-op while finalizing).
            // Taps that act vibrate; ignored ones don't.
            onButtonTap = {
                when (dictation.state.value) {
                    is DictationState.Finalizing -> return@BubbleOverlay
                    is DictationState.Listening -> dictation.cancel()
                    else -> dictation.start()
                }
                haptic()
            },
            onPillTap = {
                if (dictation.state.value is DictationState.Listening) {
                    target = focusedField
                    stoppedAt = SystemClock.elapsedRealtime()
                    dictation.stop()
                    haptic()
                }
            },
            onMoved = { side, yFraction -> scope.launch { repository.setBubblePosition(side, yFraction) } }
        )
        scope.launch {
            repository.settings.collect {
                sttWarmUp = if (it.sttKeySet) container.speechToText(it) else null
                overlay.setPanelSize(it.panelLines, it.panelWidthPercent) // Skips unchanged values.
            }
        }
        scope.launch { dictation.level.collect { this@IrisAccessibilityService.overlay?.setLevel(it) } }
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
        overlay?.hide(animate = false)
        overlay = null
        dictation = null
        fieldFocused = false
        focusedField = null
        target = null
        scope.cancel() // Also cancels any dictation session, which releases the mic and closes the socket.
        super.onDestroy()
    }

    private fun refreshFocus() {
        val overlay = overlay ?: return
        // Iris's own screens (API keys) are skipped; so is any password field. An open keyboard counts as a field
        // (the IME window's add/remove events call this), since some apps' fields aren't reported as editable.
        val inputFocus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val keyboardOnly = inputFocus?.isEditable != true && imeTop() != null
        // A Compose app (e.g. ChatGPT) may report only its host view until its node tree is walked once.
        val focused = if (keyboardOnly) rootInActiveWindow?.let(::findFocusedEditable) ?: inputFocus else inputFocus
        fieldFocused = when {
            focused?.isPassword == true -> false
            focused != null -> focused.packageName?.toString() != packageName && (focused.isEditable || keyboardOnly)
            else -> keyboardOnly && rootInActiveWindow?.packageName?.toString() != packageName
        }
        focusedField = focused?.takeIf { fieldFocused }
        if (fieldFocused) {
            handler.removeCallbacks(hideBubble)
            overlay.show()
            dictation?.let { render(it.state.value) } // Restores the panel if the bubble was hidden mid-session.
            warmUpStt()
        } else if (!handler.hasCallbacks(hideBubble)) {
            handler.postDelayed(hideBubble, HIDE_DELAY_MS)
        }
    }

    /** The first focused editable node under [root], breadth first, visiting at most [MAX_TREE_NODES] nodes. */
    private fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque(listOf(root))
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_TREE_NODES) {
            val node = queue.removeFirst()
            if (node.isFocused && node.isEditable) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    /** Pre-warms the STT connection while the bubble is up and no session runs; the provider throttles repeats. */
    private fun warmUpStt() {
        val state = dictation?.state?.value ?: return
        val stt = sttWarmUp ?: return
        if (!fieldFocused) return
        if (state is DictationState.Listening || state is DictationState.Finalizing) return
        stt.warmUp()
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
            // The panel comes in with the first words.
            is DictationState.Listening -> if (state.text.isBlank()) {
                overlay.hideText()
            } else {
                overlay.showText(state.text)
            }
            is DictationState.Finalizing -> if (state.text.isBlank()) {
                overlay.hideText()
            } else {
                overlay.showText(state.text)
            }
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
     * optional LLM cleanup, then inserts at the cursor of [target] if it still has focus, else copies to the
     * clipboard. A failed or timed-out cleanup inserts the original transcript, and any other failure copies the
     * text, so the dictation is never lost.
     */
    private suspend fun onFinalTranscript(transcript: String): DictationState.Done {
        transcriptAt = SystemClock.elapsedRealtime()
        var text = transcript
        var cleanupError: String? = null
        var cleanupMs: Long? = null
        var cleanupTimedOut = false
        var targetFocused = false
        val insertion = try {
            val container = (application as IrisApp).container
            val settings = container.settingsRepository.settings.first()
            if (settings.cleanupEnabled) {
                val prompt = settings.activePrompt ?: Settings.DEFAULT_PROMPT
                val cleanupStart = SystemClock.elapsedRealtime()
                try {
                    // OrNull: a plain withTimeout's exception is a CancellationException and would be rethrown.
                    val cleaned = withTimeoutOrNull(settings.cleanupTimeoutSeconds * 1000L) {
                        container.cleanup(settings).cleanup(
                            transcript,
                            prompt.systemPrompt,
                            settings.activeCleanupModel,
                            settings.activeCleanupReasoningEffort,
                            parseProviderOptions(settings.activeCleanupProviderOptions)
                        )
                    }
                    if (cleaned != null) {
                        text = cleaned
                    } else {
                        cleanupTimedOut = true
                        cleanupError = "Cleanup timeout."
                    }
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
            // A null target (keyboard only, no focused node) can't be matched: insertText copies the text.
            targetFocused = target == null || target == focusedField
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
        val cleanupOutcome = when {
            cleanupTimedOut -> " timed out"
            cleanupError != null -> " failed"
            else -> ""
        }
        val cleanupTiming = cleanupMs?.let { "$it ms$cleanupOutcome" } ?: "off"
        Log.d(
            TAG,
            "Timing: stop->transcript ${transcriptAt - stoppedAt} ms, cleanup $cleanupTiming, " +
                "stop->done ${SystemClock.elapsedRealtime() - stoppedAt} ms"
        )
        val inserted = targetFocused && insertion != null && insertion != Insertion.COPIED
        val notice = if (cleanupTimedOut && inserted) {
            "Cleanup timeout. Pasted original text."
        } else {
            doneNotice(cleanupError, targetFocused, insertion)
        }
        return DictationState.Done(text, notice)
    }

    /** A strong, short click; as touch feedback, so the user's touch-haptics setting applies. */
    private fun haptic() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }
        if (!vibrator.hasVibrator()) return
        val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                effect,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()
            )
        }
    }

    private fun imeTop(): Int? = windows
        .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        ?.let { window -> Rect().also(window::getBoundsInScreen).top }

    private companion object {
        const val TAG = "IrisAccessibilityService"

        /** Caps the keyboard-only tree walk (a node is one IPC unless prefetched). */
        const val MAX_TREE_NODES = 1000
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
