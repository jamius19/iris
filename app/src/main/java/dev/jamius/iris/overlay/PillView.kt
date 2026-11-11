/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AnimationUtils
import dev.jamius.iris.R
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The red stop pill with a live waveform (horizontal bars stacked vertically), shown below the bubble's circle
 * (above it if [opensUp]). The view spans from the circle's centre to the pill's far end plus [MARGIN_DP] of shadow
 * room; [reveal] slides the pill out of the circle (0 = hidden behind it, the rest clipped by the parent).
 */
internal class PillView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val margin = MARGIN_DP * density
    private val pillWidth = PILL_WIDTH_DP * density
    private val pillHeight = PILL_HEIGHT_DP * density
    private val radius = pillWidth / 2
    private val travel = (PILL_HEIGHT_DP + GAP_DP) * density
    private val widthPx = (pillWidth + 2 * margin).roundToInt()

    /** View height: half the circle, the gap, the pill and the shadow margin. */
    val heightPx = ((PILL_WIDTH_DP / 2 + GAP_DP + PILL_HEIGHT_DP + MARGIN_DP) * density).roundToInt()

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pill_background)
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pill_bar)
        strokeWidth = BAR_STROKE_DP * density
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()
    private val outlinePath = Path()
    private val boundsPath = Path()

    /** The pill opens upward: it sits at the view's top and the circle is below the view. */
    var opensUp = false
        set(value) {
            field = value
            invalidateOutline()
            invalidate()
        }

    /** 0 = hidden behind the circle, 1 = fully out. */
    var reveal = 0f
        set(value) {
            field = value
            invalidateOutline()
            invalidate()
        }

    /** True while listening: the bars follow [setLevel]; otherwise they shrink back to dots. */
    var active = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                lastFrameMs = 0L
                shown = 0f
            }
            invalidate()
        }

    private var target = 0f
    private var shown = 0f
    private var lastFrameMs = 0L

    init {
        contentDescription = context.getString(R.string.pill_stop)
        elevation = 3 * density // Below the bubble's 5 dp, so the circle draws on top.
        // Only the part inside the view casts a shadow: the rest of the pill isn't drawn (clipped), and its shadow
        // would show as a grey band cut off by the window's edge.
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                updateRect()
                outlinePath.reset()
                outlinePath.addRoundRect(rect, radius, radius, Path.Direction.CW)
                boundsPath.reset()
                boundsPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
                outlinePath.op(boundsPath, Path.Op.INTERSECT)
                outline.setPath(outlinePath)
            }
        }
    }

    /** Latest loudness, 0..1. */
    fun setLevel(level: Float) {
        target = level
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(widthPx, heightPx)
    }

    /** The pill's rect at the current [reveal]. */
    private fun updateRect() {
        val top = if (opensUp) margin else heightPx - margin - pillHeight
        val offset = (1 - reveal) * travel * if (opensUp) 1 else -1
        rect.set(margin, top + offset, margin + pillWidth, top + offset + pillHeight)
    }

    override fun onDraw(canvas: Canvas) {
        updateRect()
        canvas.drawRoundRect(rect, radius, radius, backgroundPaint)

        // Fast attack, slow release, frame-rate independent. Also after stop (target 0), so the bars shrink to dots
        // while the collapse animation redraws.
        val now = AnimationUtils.currentAnimationTimeMillis()
        val dt = if (lastFrameMs == 0L) 0L else (now - lastFrameMs).coerceIn(0L, 100L)
        lastFrameMs = now
        val tau = if (target > shown) ATTACK_MS else RELEASE_MS
        shown += (target - shown) * (1 - exp(-dt / tau))
        val centerX = rect.centerX()
        val firstY = rect.top + BAR_PADDING_DP * density
        val step = (PILL_HEIGHT_DP - 2 * BAR_PADDING_DP) * density / (BAR_WEIGHTS.size - 1)
        val maxLength = (BAR_MAX_DP - BAR_STROKE_DP) * density // Round caps add the stroke width.
        for (i in BAR_WEIGHTS.indices) {
            val cycle = (now % BAR_PERIODS_MS[i]).toFloat() / BAR_PERIODS_MS[i]
            val wobble = 1 + 0.15f * sin(2 * PI.toFloat() * cycle + BAR_PHASES[i])
            val half = maxLength * (shown * BAR_WEIGHTS[i] * wobble).coerceIn(0f, 1f) / 2
            val y = firstY + i * step
            canvas.drawLine(centerX - half, y, centerX + half, y, barPaint)
        }
        if ((active || shown > SETTLED) && isAttachedToWindow) postInvalidateOnAnimation()
    }

    private companion object {
        const val MARGIN_DP = 6f
        const val PILL_WIDTH_DP = 40f
        const val PILL_HEIGHT_DP = 96f
        const val GAP_DP = 6f
        const val BAR_STROKE_DP = 4.5f
        const val BAR_MAX_DP = 23.5f
        const val BAR_PADDING_DP = 18f
        const val ATTACK_MS = 40f
        const val RELEASE_MS = 200f
        const val SETTLED = 0.001f
        val BAR_WEIGHTS = floatArrayOf(0.55f, 0.8f, 1f, 0.95f, 0.75f, 0.5f)
        val BAR_PERIODS_MS = longArrayOf(700, 950, 600, 1100, 800, 1000)
        val BAR_PHASES = floatArrayOf(0f, 1.3f, 2.6f, 0.7f, 3.9f, 5.1f)
    }
}
