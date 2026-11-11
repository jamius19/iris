/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.animation.doOnEnd
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import dev.jamius.iris.R
import dev.jamius.iris.data.settings.BubbleSide
import dev.jamius.iris.data.settings.Settings
import kotlin.math.roundToInt

/**
 * The bubble and live-text panel, drawn as two `TYPE_ACCESSIBILITY_OVERLAY` windows. Must be created with an
 * AccessibilityService context and used on the main thread.
 *
 * The bubble window is a stack: the button ([BubbleView]) and, while listening, the stop pill ([PillView]) sliding
 * out below it, or above it if it wouldn't fit below ([pillOpensUp]). The pill is GONE when collapsed, so the
 * WRAP_CONTENT window is just the button at rest.
 *
 * The overlay owns no dictation logic: [onButtonTap] and [onPillTap] report taps, the owner drives [setStatus],
 * [setLevel] and [showText]/[hideText]. [onMoved] reports where the user dropped the bubble, for persisting.
 */
class BubbleOverlay(
    private val context: Context,
    private val onButtonTap: () -> Unit,
    private val onPillTap: () -> Unit,
    private val onMoved: (side: BubbleSide, yFraction: Float) -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density

    private val bubble = BubbleView(context)
    private val pill = PillView(context).apply { visibility = View.GONE }

    /** Distance from the stack's button-side edge to the pill view: the pill view starts at the circle's centre. */
    private val pillMargin = bubble.circleInsetPx + bubble.circlePx / 2
    private val expandedHeight = pillMargin + pill.heightPx
    private val stack = FrameLayout(context).apply {
        addView(pill, FrameLayout.LayoutParams(WRAP, WRAP).apply { topMargin = pillMargin })
        addView(bubble, FrameLayout.LayoutParams(WRAP, WRAP))
    }
    private val bubbleParams = overlayParams(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

    /** The stack window is added, also while it animates out ([stackExiting]); false once it is removed. */
    private var bubbleShown = false

    /** [hide] is animating the stack out; it is removed at the end unless [show] brings it back first. */
    private var stackExiting = false
    private var stackAnimator: ValueAnimator? = null

    /** Display y of the button's top; the window's y differs while the pill opens up. */
    private var buttonY = 0
    private var expanded = false
    private var revealAnimator: ValueAnimator? = null

    /** The pill's direction, decided while it is visible and kept after it collapses, so the panel doesn't jump. */
    private var pillUp = false

    // Panel size from settings (setPanelSize()); declared before the panel, which reads liveLines.
    private var liveLines = Settings.DEFAULT_PANEL_LINES
    private var widthPercent = Settings.DEFAULT_PANEL_WIDTH_PERCENT

    // Live text is trimmed to its last liveLines lines (see lastLines()), so the panel never scrolls; other text
    // shows from its start, up to max(liveLines, NOTICE_MIN_LINES) lines. Simple line breaking and no font padding
    // keep its layout and height predictable.
    private val panel = TextView(context).apply {
        setTextColor(context.getColor(R.color.panel_text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        lineHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics).roundToInt()
        includeFontPadding = false
        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        maxLines = liveLines
        // Vertical padding makes one line exactly as tall as the bubble's circle.
        val padV = (16 * density).roundToInt()
        val padH = (16 * density).roundToInt()
        setPadding(padH, (padV / 1.5f).toInt(), padH, (padV / 1.5f).toInt())
        background = GradientDrawable().apply {
            setColor(context.getColor(R.color.panel_background))
            cornerRadius = bubble.circlePx / 2.5f
        }
    }
    private var panelText = ""
    private var panelTail = true
    private val panelParams = overlayParams(
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    )
    private var panelShown = false

    /** The owner wants the panel shown (kept while the bubble is hidden, so [show] restores it). */
    private var panelWanted = false
    private var panelExiting = false
    private var panelAnimator: ValueAnimator? = null
    private var panelAlignBottom = false

    private var side = Settings.DEFAULT_BUBBLE_SIDE
    private var yFraction = Settings.DEFAULT_BUBBLE_Y_FRACTION
    private var imeTop: Int? = null
    private var snapAnimator: ValueAnimator? = null

    init {
        // DragHandler calls performClick() on a tap.
        val dragHandler = DragHandler()
        @SuppressLint("ClickableViewAccessibility")
        bubble.setOnTouchListener(dragHandler)
        @SuppressLint("ClickableViewAccessibility")
        pill.setOnTouchListener(dragHandler)
        // Not while animating out: the bubble is going away.
        bubble.setOnClickListener { if (!stackExiting) onButtonTap() }
        pill.setOnClickListener { if (!stackExiting) onPillTap() }
    }

    /**
     * Fades and zooms the bubble in, and the panel if it should show (see [showText]), in their current state (no
     * replayed status/pill animations). During [hide]'s exit animation, both turn back from where they are.
     */
    fun show() {
        if (bubbleShown && !stackExiting) return
        if (!bubbleShown) {
            placeBubble()
            stack.alpha = 0f
            try {
                windowManager.addView(stack, bubbleParams)
                bubbleShown = true
            } catch (e: WindowManager.BadTokenException) {
                Log.w(TAG, "Bubble not shown", e) // The service is being torn down.
                return
            }
        }
        animateStack(enter = true)
        if (panelWanted) {
            // The panel may still be animating out (it outlasts the stack's exit), or be gone.
            if (!panelShown) {
                placePanel()
                panel.alpha = 0f
            }
            if (panelShown || addPanel()) {
                animatePanel(enter = true)
                setPanelText()
            }
        }
    }

    /**
     * Fades and zooms the bubble out, then removes its window; the panel animates out alongside. Until removed,
     * [bubbleShown] stays true and taps are ignored; [showText] keeps the text for [show]. With [animate] false
     * (teardown) both windows are removed at once. The status and panel text are left to the owner.
     */
    fun hide(animate: Boolean = true) {
        if (!animate) {
            stackAnimator?.cancel()
            panelAnimator?.cancel()
            removePanel()
            removeStack()
            return
        }
        if (panelShown && !panelExiting) animatePanel(enter = false)
        if (bubbleShown && !stackExiting) animateStack(enter = false)
    }

    private fun removeStack() {
        snapAnimator?.takeIf { it.isRunning }?.end()
        revealAnimator?.takeIf { it.isRunning }?.end()
        stackExiting = false
        if (!bubbleShown) return
        windowManager.removeViewImmediate(stack) // See removePanel().
        bubbleShown = false
    }

    /** Fades and zooms the stack in or out (then removes it) around the circle's centre; continues from mid-way. */
    private fun animateStack(enter: Boolean) {
        stackExiting = !enter // Before the cancel below, so a cancelled exit doesn't remove the stack.
        stackAnimator?.cancel()
        stackAnimator = ValueAnimator.ofFloat(stack.alpha, if (enter) 1f else 0f).apply {
            duration = if (enter) STACK_IN_MS else STACK_OUT_MS
            interpolator = if (enter) EMPHASIZED_DECELERATE else EMPHASIZED_ACCELERATE
            addUpdateListener {
                val fraction = it.animatedValue as Float
                val scale = STACK_MIN_SCALE + (1 - STACK_MIN_SCALE) * fraction
                stack.alpha = fraction
                stack.scaleX = scale
                stack.scaleY = scale
                // Set per frame: the pill may flip meanwhile, moving the button within the stack (see placeStack()).
                stack.pivotX = bubble.sizePx / 2f
                stack.pivotY = buttonY - bubbleParams.y + bubble.sizePx / 2f
            }
            if (!enter) doOnEnd { if (stackExiting) removeStack() }
            start()
        }
    }

    /** Also expands the pill while [BubbleStatus.LISTENING] and collapses it otherwise. */
    fun setStatus(status: BubbleStatus) {
        bubble.status = status
        setExpanded(status == BubbleStatus.LISTENING)
    }

    /** Loudness (0..1) for the pill's waveform. */
    fun setLevel(level: Float) = pill.setLevel(level)

    private fun setExpanded(expanded: Boolean) {
        if (expanded == this.expanded) return
        this.expanded = expanded
        pill.active = expanded
        revealAnimator?.run {
            removeAllListeners()
            cancel()
        }
        if (expanded) {
            pill.visibility = View.VISIBLE
            placeStack()
            if (bubbleShown) updateWindows() // Applied in the same traversal as the window's WRAP_CONTENT growth.
        }
        if (!bubbleShown) {
            pill.reveal = if (expanded) 1f else 0f
            if (!expanded) collapsed()
            return
        }
        revealAnimator = ValueAnimator.ofFloat(pill.reveal, if (expanded) 1f else 0f).apply {
            duration = PILL_MS
            interpolator = EASE_IN_OUT
            addUpdateListener { pill.reveal = it.animatedValue as Float }
            if (!expanded) doOnEnd { collapsed() }
            start()
        }
    }

    /** The pill is back behind the circle: shrink the window to the button. */
    private fun collapsed() {
        pill.visibility = View.GONE
        placeStack()
        updateWindows()
    }

    /**
     * Shows [text] in the panel beside the bubble, animating the panel in if it isn't showing. While hidden, it is
     * kept for [show]. With [tail] (live text) it shows the last [liveLines] lines; otherwise (notices, errors) up to
     * max([liveLines], [NOTICE_MIN_LINES]) lines from the start, ellipsized.
     */
    fun showText(text: String, tail: Boolean = true) {
        panelText = text
        panelWanted = true
        val modeChanged = tail != panelTail
        if (modeChanged) {
            panelTail = tail
            panel.maxLines = panelMaxLines()
            panel.ellipsize = if (tail) null else TextUtils.TruncateAt.END
        }
        if (!bubbleShown || stackExiting) return
        // Placed when added or when the max height changes; updateWindows() moves it with the bubble.
        if (!panelShown) {
            placePanel()
            panel.alpha = 0f
            if (!addPanel()) return
            animatePanel(enter = true)
        } else {
            if (panelExiting) animatePanel(enter = true)
            if (modeChanged) {
                placePanel()
                windowManager.updateViewLayout(panel, panelParams)
            }
        }
        setPanelText()
    }

    /** Animates the panel out (see [showText]). */
    fun hideText() {
        panelWanted = false
        if (panelShown && !panelExiting) animatePanel(enter = false)
    }

    private fun addPanel(): Boolean {
        try {
            windowManager.addView(panel, panelParams)
            panelShown = true
        } catch (e: WindowManager.BadTokenException) {
            Log.w(TAG, "Panel not shown", e) // The service is being torn down.
        }
        return panelShown
    }

    private fun removePanel() {
        panelExiting = false
        if (!panelShown) return
        // Not removeView(): on Android 13-16 it frees the hardware renderer before the frame already scheduled, which
        // then draws in software, ignoring alpha/scale/shadows (the faded panel flashed at full opacity). Safe here:
        // only called from animator callbacks and hide(), never during the window's own layout or draw.
        windowManager.removeViewImmediate(panel)
        panelShown = false
    }

    /** Fades and scales the panel in or out (then removes it), from the bubble's side; continues from mid-way. */
    private fun animatePanel(enter: Boolean) {
        panelExiting = !enter // Before the cancel below, so a cancelled exit doesn't remove the panel.
        panelAnimator?.cancel()
        panelAnimator = ValueAnimator.ofFloat(panel.alpha, if (enter) 1f else 0f).apply {
            duration = if (enter) PANEL_IN_MS else PANEL_OUT_MS
            interpolator = if (enter) EMPHASIZED_DECELERATE else EMPHASIZED_ACCELERATE
            addUpdateListener {
                val fraction = it.animatedValue as Float
                val scale = PANEL_MIN_SCALE + (1 - PANEL_MIN_SCALE) * fraction
                panel.alpha = fraction
                panel.scaleX = scale
                panel.scaleY = scale
                // Set per frame: the size is known only after layout and may grow meanwhile.
                panel.pivotX = if (side == BubbleSide.RIGHT) panel.width.toFloat() else 0f
                panel.pivotY = if (panelAlignBottom) panel.height.toFloat() else 0f
            }
            if (!enter) doOnEnd { if (panelExiting) removePanel() }
            start()
        }
    }

    private fun setPanelText() {
        if (!panelTail) {
            panel.text = panelText
            return
        }
        panel.text = lastLines(panelText)
        // Backstop: if the TextView breaks lines differently than lastLines() did, re-trim from its own layout once,
        // so the newest words still show. Without ellipsize, its layout holds every line despite maxLines.
        panel.doOnLayout {
            val layout = panel.layout ?: return@doOnLayout
            if (panelTail && layout.lineCount > liveLines) {
                panel.text = panel.text.substring(layout.getLineStart(layout.lineCount - liveLines))
            }
        }
    }

    private fun panelMaxLines() = if (panelTail) liveLines else maxOf(liveLines, NOTICE_MIN_LINES)

    /** The end of [text] that fills the panel's last [liveLines] lines at its max width; older lines drop off. */
    private fun lastLines(text: String): String {
        val width = (panel.maxWidth - panel.paddingLeft - panel.paddingRight).coerceAtLeast(1)
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, panel.paint, width)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        // Match the TextView, which uses bounds for width when targeting API 35+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            builder.setUseBoundsForWidth(panel.useBoundsForWidth)
        }
        val layout = builder.build()
        val lines = layout.lineCount
        return if (lines <= liveLines) text else text.substring(layout.getLineStart(lines - liveLines))
    }

    /** Whether [windowId] (an accessibility window id) is the bubble's or the panel's window. */
    fun ownsWindow(windowId: Int): Boolean = listOf(stack, panel).any {
        it.isAttachedToWindow && it.createAccessibilityNodeInfo().windowId == windowId
    }

    /** Restores a persisted position. */
    fun setPosition(side: BubbleSide, yFraction: Float) {
        this.side = side
        this.yFraction = yFraction
        relayout()
    }

    /**
     * Live text keeps its last [lines] lines (notices up to max([lines], 4)); max width is [widthPercent] % of the
     * display.
     */
    fun setPanelSize(lines: Int, widthPercent: Int) {
        if (lines == liveLines && widthPercent == this.widthPercent) return
        liveLines = lines
        this.widthPercent = widthPercent
        panel.maxLines = panelMaxLines()
        if (!panelShown) return // show() / showText() place it with the new size.
        placePanel()
        windowManager.updateViewLayout(panel, panelParams)
        setPanelText() // Re-trims from the full panelText, so more lines bring older words back.
    }

    /** Top of the on-screen keyboard in display pixels, or null if none is shown. The bubble stays above it. */
    fun setImeTop(top: Int?) {
        if (top == imeTop) return
        imeTop = top
        relayout()
    }

    /** Call on rotation or screen size change: re-snaps the bubble to its edge and keeps it on screen. */
    fun onConfigurationChanged() {
        snapAnimator?.takeIf { it.isRunning }?.end()
        stackAnimator?.takeIf { it.isRunning }?.end() // Fully in, or removed (an exit removes the stack).
        bubble.onConfigurationChanged() // Dark mode colours.
        relayout()
    }

    private fun relayout() {
        placeBubble()
        updateWindows()
    }

    private fun placeBubble() {
        val size = bubble.sizePx
        val area = screenArea()
        val visible = area.aboveIme()
        bubbleParams.x = snappedX(side, size, area)
        buttonY = clamp(yForFraction(yFraction, size, area), visible.top, visible.bottom, size)
        placeStack()
    }

    /**
     * While the pill is visible, opens it down from the button at [buttonY], or up if it wouldn't fit above the
     * IME/usable bottom; then lays out the stack and sets the window's y so the button stays at [buttonY].
     */
    private fun placeStack() {
        val visible = pill.visibility == View.VISIBLE
        if (visible) pillUp = pillOpensUp(buttonY, expandedHeight, screenArea().aboveIme().bottom)
        val up = visible && pillUp
        if (up != pill.opensUp) {
            pill.opensUp = up
            val gravity = if (up) Gravity.BOTTOM else Gravity.TOP
            bubble.updateLayoutParams<FrameLayout.LayoutParams> { this.gravity = gravity }
            pill.updateLayoutParams<FrameLayout.LayoutParams> {
                this.gravity = gravity
                topMargin = if (up) 0 else pillMargin
                // The button's view also has shadow room below the circle.
                bottomMargin = if (up) pillMargin + bubble.shadowRoomPx else 0
            }
        }
        bubbleParams.y = if (up) buttonY - (expandedHeight - bubble.sizePx) else buttonY
    }

    // LEFT/RIGHT are deliberate: overlay coordinates are display coordinates, not layout direction.
    @SuppressLint("RtlHardcoded")
    private fun placePanel() {
        val bounds = windowManager.maximumWindowMetrics.bounds
        val area = screenArea()
        val gap = (4 * density).roundToInt()
        val maxWidth = panelMaxWidth(bounds.width(), widthPercent, area, bubble.sizePx, gap)
        if (maxWidth != panel.maxWidth) {
            panel.maxWidth = maxWidth
            // Re-trim for the new width, e.g. after rotation. Before it's added, showText() sets the text.
            if (panelShown) setPanelText()
        }
        val placement = panelPlacement(
            side = side,
            bubbleX = bubbleParams.x,
            bubbleSize = bubble.sizePx,
            circleTop = buttonY + bubble.circleInsetPx,
            circleSize = bubble.circlePx,
            panelMaxHeight = panel.maxLines * panel.lineHeight + panel.paddingTop + panel.paddingBottom,
            displayWidth = bounds.width(),
            displayHeight = bounds.height(),
            visible = area.aboveIme(),
            gap = gap,
            growUp = pillUp,
        )
        panelParams.gravity = (if (placement.alignRight) Gravity.RIGHT else Gravity.LEFT) or
            (if (placement.alignBottom) Gravity.BOTTOM else Gravity.TOP)
        panelParams.x = placement.x
        panelParams.y = placement.y
        panelAlignBottom = placement.alignBottom
    }

    private fun updateWindows() {
        if (bubbleShown) windowManager.updateViewLayout(stack, bubbleParams)
        if (panelShown) {
            placePanel()
            windowManager.updateViewLayout(panel, panelParams)
        }
    }

    /** Display area minus system bars and cutouts. Overlay window coordinates are display coordinates. */
    private fun screenArea(): Area {
        val metrics = windowManager.maximumWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        return Area(
            left = insets.left,
            top = insets.top,
            right = metrics.bounds.width() - insets.right,
            bottom = metrics.bounds.height() - insets.bottom,
        )
    }

    private fun Area.aboveIme(): Area = imeTop?.let { copy(bottom = minOf(bottom, it)) } ?: this

    /** Drags the bubble once the pointer passes touch slop; snaps it to the nearest edge on release. */
    private inner class DragHandler : View.OnTouchListener {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var clickTarget: View? = null

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.takeIf { it.isRunning }?.end()
                    downX = event.rawX
                    downY = event.rawY
                    startX = bubbleParams.x
                    startY = buttonY
                    dragging = false
                    // While the pill is out, the button's margin on the pill's side (the gap) counts as the pill.
                    val inGap = if (pill.opensUp) {
                        event.y < bubble.circleInsetPx
                    } else {
                        event.y > bubble.circleInsetPx + bubble.circlePx
                    }
                    clickTarget = if (view === bubble && expanded && inGap) pill else view
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && isDrag(dx, dy, touchSlop)) dragging = true
                    if (dragging) {
                        val size = bubble.sizePx
                        val visible = screenArea().aboveIme()
                        bubbleParams.x = clamp(startX + dx.roundToInt(), visible.left, visible.right, size)
                        buttonY = clamp(startY + dy.roundToInt(), visible.top, visible.bottom, size)
                        placeStack()
                        updateWindows()
                    }
                }
                MotionEvent.ACTION_UP -> if (dragging) snapToEdge() else clickTarget?.performClick()
                MotionEvent.ACTION_CANCEL -> if (dragging) snapToEdge()
            }
            return true
        }
    }

    private fun snapToEdge() {
        val size = bubble.sizePx
        val area = screenArea()
        side = nearestSide(bubbleParams.x, size, area)
        yFraction = fractionForY(buttonY, size, area)
        onMoved(side, yFraction)
        snapAnimator = ValueAnimator.ofInt(bubbleParams.x, snappedX(side, size, area)).apply {
            duration = SNAP_DURATION_MS
            addUpdateListener {
                bubbleParams.x = it.animatedValue as Int
                updateWindows()
            }
            start()
        }
    }

    // LEFT is deliberate: overlay coordinates are display coordinates, not layout direction.
    @SuppressLint("RtlHardcoded")
    private fun overlayParams(flags: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        flags,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        // Position in raw display coordinates; screenArea() keeps the bubble clear of bars and cutouts.
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        // The system would otherwise animate a move (e.g. the stack growing up from the button), sliding the
        // already re-laid-out content from the old position. API 33 and below can't turn it off.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) setCanPlayMoveAnimation(false)
    }

    private companion object {
        const val TAG = "BubbleOverlay"
        const val NOTICE_MIN_LINES = 4
        const val SNAP_DURATION_MS = 150L
        const val PILL_MS = 300L
        const val PANEL_IN_MS = 250L
        const val PANEL_OUT_MS = 200L
        const val PANEL_MIN_SCALE = 0.92f
        const val STACK_IN_MS = 200L
        const val STACK_OUT_MS = 150L
        const val STACK_MIN_SCALE = 0.6f
        const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
        val EMPHASIZED_DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        val EMPHASIZED_ACCELERATE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
        val EASE_IN_OUT = PathInterpolator(0.65f, 0f, 0.35f, 1f)
    }
}
