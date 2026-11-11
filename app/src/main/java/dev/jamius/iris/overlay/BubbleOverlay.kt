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
import android.widget.TextView
import androidx.core.view.doOnLayout
import dev.jamius.iris.R
import dev.jamius.iris.data.settings.BubbleSide
import dev.jamius.iris.data.settings.Settings
import kotlin.math.roundToInt

/**
 * The bubble and live-text panel, drawn as two `TYPE_ACCESSIBILITY_OVERLAY` windows. Must be created with an
 * AccessibilityService context and used on the main thread.
 *
 * The overlay owns no dictation logic: [onTap] reports taps, the owner drives [setStatus] and
 * [showText]/[hideText]. [onMoved] reports where the user dropped the bubble, for persisting.
 */
class BubbleOverlay(
    private val context: Context,
    private val onTap: () -> Unit,
    private val onMoved: (side: BubbleSide, yFraction: Float) -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density

    private val bubble = BubbleView(context)
    private val bubbleParams = overlayParams(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    private var bubbleShown = false

    // Panel size from settings (setPanelSize()); declared before the panel, which reads liveLines.
    private var liveLines = Settings.DEFAULT_PANEL_LINES
    private var widthPercent = Settings.DEFAULT_PANEL_WIDTH_PERCENT

    // Live text is trimmed to its last liveLines lines (see lastLines()), so the panel never scrolls; other text
    // shows from its start, up to max(liveLines, NOTICE_MIN_LINES) lines. Simple line breaking and no font padding
    // keep its layout and height predictable.
    private val panel = TextView(context).apply {
        setTextColor(context.getColor(R.color.panel_text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        includeFontPadding = false
        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        maxLines = liveLines
        // Vertical padding makes one line exactly as tall as the bubble's circle.
        val padV = (bubble.circlePx - lineHeight).coerceAtLeast(0)
        val padH = (14 * density).roundToInt()
        setPadding(padH, padV / 2, padH, padV - padV / 2)
        background = GradientDrawable().apply {
            setColor(context.getColor(R.color.panel_background))
            cornerRadius = bubble.circlePx / 2f
        }
    }
    private var panelText = ""
    private var panelTail = true
    private val panelParams = overlayParams(
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    )
    private var panelShown = false

    private var side = Settings.DEFAULT_BUBBLE_SIDE
    private var yFraction = Settings.DEFAULT_BUBBLE_Y_FRACTION
    private var imeTop: Int? = null
    private var snapAnimator: ValueAnimator? = null

    init {
        // DragHandler calls performClick() on a tap.
        @SuppressLint("ClickableViewAccessibility")
        bubble.setOnTouchListener(DragHandler())
        bubble.setOnClickListener { onTap() }
    }

    fun show() {
        if (bubbleShown) return
        placeBubble()
        try {
            windowManager.addView(bubble, bubbleParams)
            bubbleShown = true
        } catch (e: WindowManager.BadTokenException) {
            Log.w(TAG, "Bubble not shown", e) // The service is being torn down.
        }
    }

    /** Hides the bubble and the panel. The status is left to the owner. */
    fun hide() {
        snapAnimator?.end()
        hideText()
        if (!bubbleShown) return
        windowManager.removeView(bubble)
        bubbleShown = false
    }

    fun setStatus(status: BubbleStatus) {
        bubble.status = status
    }

    /**
     * Shows [text] in the panel beside the bubble. No-op while hidden. With [tail] (live text) it shows the last
     * [liveLines] lines; otherwise (notices, errors) up to max([liveLines], [NOTICE_MIN_LINES]) lines from the start,
     * ellipsized.
     */
    fun showText(text: String, tail: Boolean = true) {
        if (!bubbleShown) return
        panelText = text
        val modeChanged = tail != panelTail
        if (modeChanged) {
            panelTail = tail
            panel.maxLines = panelMaxLines()
            panel.ellipsize = if (tail) null else TextUtils.TruncateAt.END
        }
        // Placed when added or when the max height changes; updateWindows() moves it with the bubble.
        if (!panelShown) {
            placePanel()
            try {
                windowManager.addView(panel, panelParams)
                panelShown = true
            } catch (e: WindowManager.BadTokenException) {
                Log.w(TAG, "Panel not shown", e) // The service is being torn down.
            }
        } else if (modeChanged) {
            placePanel()
            windowManager.updateViewLayout(panel, panelParams)
        }
        setPanelText()
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

    fun hideText() {
        if (!panelShown) return
        windowManager.removeView(panel)
        panelShown = false
    }

    /** Whether [windowId] (an accessibility window id) is the bubble's or the panel's window. */
    fun ownsWindow(windowId: Int): Boolean = listOf(bubble, panel).any {
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
        snapAnimator?.end()
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
        bubbleParams.y = clamp(yForFraction(yFraction, size, area), visible.top, visible.bottom, size)
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
            circleTop = bubbleParams.y + bubble.circleInsetPx,
            circleSize = bubble.circlePx,
            panelMaxHeight = panel.maxLines * panel.lineHeight + panel.paddingTop + panel.paddingBottom,
            displayWidth = bounds.width(),
            displayHeight = bounds.height(),
            visible = area.aboveIme(),
            gap = gap,
        )
        panelParams.gravity = (if (placement.alignRight) Gravity.RIGHT else Gravity.LEFT) or
            (if (placement.alignBottom) Gravity.BOTTOM else Gravity.TOP)
        panelParams.x = placement.x
        panelParams.y = placement.y
    }

    private fun updateWindows() {
        if (bubbleShown) windowManager.updateViewLayout(bubble, bubbleParams)
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

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.end()
                    downX = event.rawX
                    downY = event.rawY
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && isDrag(dx, dy, touchSlop)) dragging = true
                    if (dragging) {
                        val size = bubble.sizePx
                        val visible = screenArea().aboveIme()
                        bubbleParams.x = clamp(startX + dx.roundToInt(), visible.left, visible.right, size)
                        bubbleParams.y = clamp(startY + dy.roundToInt(), visible.top, visible.bottom, size)
                        updateWindows()
                    }
                }
                MotionEvent.ACTION_UP -> if (dragging) snapToEdge() else view.performClick()
                MotionEvent.ACTION_CANCEL -> if (dragging) snapToEdge()
            }
            return true
        }
    }

    private fun snapToEdge() {
        val size = bubble.sizePx
        val area = screenArea()
        side = nearestSide(bubbleParams.x, size, area)
        yFraction = fractionForY(bubbleParams.y, size, area)
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
    }

    private companion object {
        const val TAG = "BubbleOverlay"
        const val NOTICE_MIN_LINES = 4
        const val SNAP_DURATION_MS = 150L
    }
}
