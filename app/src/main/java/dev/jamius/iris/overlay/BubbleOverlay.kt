/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import dev.jamius.iris.R
import dev.jamius.iris.data.settings.BubbleSide
import dev.jamius.iris.data.settings.Settings
import kotlin.math.roundToInt

/**
 * The bubble and live-text panel, drawn as two `TYPE_ACCESSIBILITY_OVERLAY` windows. Must be created with an
 * AccessibilityService context and used on the main thread.
 *
 * The overlay owns no dictation logic: [onTap] reports taps, the owner drives [setListening] and
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

    private val panel = TextView(context).apply {
        setTextColor(context.getColor(R.color.panel_text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        maxLines = PANEL_MAX_LINES
        // Bottom gravity makes TextView keep its last line in view when the text overflows maxLines.
        gravity = Gravity.BOTTOM or Gravity.START
        val padH = (12 * density).roundToInt()
        val padV = (8 * density).roundToInt()
        setPadding(padH, padV, padH, padV)
        background = GradientDrawable().apply {
            setColor(context.getColor(R.color.panel_background))
            cornerRadius = 16 * density
        }
    }
    private val panelParams = overlayParams(
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    )
    private var panelShown = false

    private var side = Settings.DEFAULT_BUBBLE_SIDE
    private var yFraction = Settings.DEFAULT_BUBBLE_Y_FRACTION
    private var imeTop: Int? = null
    private var snapAnimator: ValueAnimator? = null

    init {
        bubble.setOnTouchListener(DragHandler())
        bubble.setOnClickListener { onTap() }
    }

    fun show() {
        if (bubbleShown) return
        placeBubble()
        windowManager.addView(bubble, bubbleParams)
        bubbleShown = true
    }

    /** Hides the bubble and the panel. Listening state is left to the owner. */
    fun hide() {
        snapAnimator?.end()
        hideText()
        if (!bubbleShown) return
        windowManager.removeView(bubble)
        bubbleShown = false
    }

    fun setListening(listening: Boolean) {
        bubble.listening = listening
    }

    /** Shows [text] in the panel beside the bubble, scrolled so the latest line is visible. No-op while hidden. */
    fun showText(text: String) {
        if (!bubbleShown) return
        panel.text = text
        // Placed once when added; updateWindows() moves it with the bubble.
        if (!panelShown) {
            placePanel()
            windowManager.addView(panel, panelParams)
            panelShown = true
        }
        // Runs after the next layout pass, once the text layout and the panel's height are known.
        panel.post {
            val textHeight = panel.layout?.height ?: return@post
            val visibleHeight = panel.height - panel.totalPaddingTop - panel.totalPaddingBottom
            panel.scrollTo(0, (textHeight - visibleHeight).coerceAtLeast(0))
        }
    }

    fun hideText() {
        if (!panelShown) return
        windowManager.removeView(panel)
        panelShown = false
    }

    /** Restores a persisted position. */
    fun setPosition(side: BubbleSide, yFraction: Float) {
        this.side = side
        this.yFraction = yFraction
        relayout()
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

    private fun placePanel() {
        val bounds = windowManager.maximumWindowMetrics.bounds
        panel.maxWidth = (bounds.width() * PANEL_MAX_WIDTH_FRACTION).roundToInt()
        val placement = panelPlacement(
            side = side,
            bubbleX = bubbleParams.x,
            bubbleY = bubbleParams.y,
            bubbleSize = bubble.sizePx,
            displayWidth = bounds.width(),
            displayHeight = bounds.height(),
            visible = screenArea().aboveIme(),
            gap = (4 * density).roundToInt(),
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
        const val PANEL_MAX_LINES = 4
        const val PANEL_MAX_WIDTH_FRACTION = 0.7f
        const val SNAP_DURATION_MS = 150L
    }
}
