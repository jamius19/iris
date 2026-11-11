/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.core.animation.doOnEnd
import androidx.core.graphics.withSave
import dev.jamius.iris.R
import kotlin.math.roundToInt

/** What the bubble shows. */
enum class BubbleStatus {
    /** Purple, with a mic. */
    IDLE,

    /** Control circle with a ✕ (cancel); the overlay shows the pill below it. */
    LISTENING,

    /** Control circle with a spinner (finalizing, cleanup, insertion). */
    WORKING,

    /** Green, with a tick. */
    SUCCESS,

    /** Red, with an exclamation mark. */
    FAILURE,
}

/**
 * The bubble: a filled circle with an icon drawn per [status]; a status change morphs colour and icon.
 * The view is larger than the circle by [MARGIN_DP] on each side (touch target and shadow room), plus
 * [shadowRoomPx] below, so the shadow, which falls downward, isn't cut by the window's bottom edge.
 */
internal class BubbleView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val radius = RADIUS_DP * density
    val sizePx = (2 * (radius + MARGIN_DP * density)).roundToInt()

    /** Extra view height below [sizePx]; [sizePx] stays the button's size for placement. */
    val shadowRoomPx = (SHADOW_ROOM_DP * density).roundToInt()

    /** Diameter of the visible circle, and its offset from the view's top/left edge (the margin). */
    val circlePx = (2 * radius).roundToInt()
    val circleInsetPx = (sizePx - circlePx) / 2

    private var idleColor = 0
    private var controlColor = 0
    private var successColor = 0
    private var failureColor = 0
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mic = icon(R.drawable.ic_mic)
    private val close = icon(R.drawable.ic_close)
    private val check = icon(R.drawable.ic_check)
    private val exclamation = icon(R.drawable.ic_exclamation)

    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = SPINNER_STROKE_DP * density
        strokeCap = Paint.Cap.ROUND
    }
    private val spinnerBounds = RectF().apply {
        val center = sizePx / 2f
        val r = (ICON_DP - SPINNER_STROKE_DP) * density / 2
        set(center - r, center - r, center + r, center + r)
    }

    private val spin = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    /**
     * The morph draws [from] -> [to] at progress [t] (1 = only [to] shows). [to] is [status], except while a
     * [reversed] morph runs back to [from] (the status was set back mid-morph).
     */
    private var from = BubbleStatus.IDLE
    private var to = BubbleStatus.IDLE
    private var t = 1f
    private var reversed = false
    private val morph = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = MORPH_MS
        interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
        doOnEnd {
            if (reversed) {
                to = from
                t = 1f
                reversed = false
            }
            updateAnimations() // Stops the spinner once it has morphed out.
        }
    }

    var status = BubbleStatus.IDLE
        set(value) {
            if (field == value) return
            field = value
            contentDescription = context.getString(description(value))
            when {
                !isAttachedToWindow -> {
                    morph.cancel()
                    to = value
                    t = 1f
                }
                // Set back to the status being morphed away from: run the morph back from where it is.
                morph.isRunning && value == (if (reversed) to else from) -> {
                    reversed = !reversed
                    morph.reverse()
                }
                else -> {
                    // Mid-morph, morph on from whichever status dominates now.
                    if (t >= 0.5f) from = to
                    to = value
                    reversed = false
                    morph.start()
                }
            }
            updateAnimations()
            invalidate()
        }

    init {
        loadColors()
        contentDescription = context.getString(description(status))
        elevation = 5 * density
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val center = sizePx / 2
                val r = radius.roundToInt()
                outline.setOval(center - r, center - r, center + r, center + r)
            }
        }
    }

    /** Re-reads the colours, e.g. after a dark mode switch. */
    fun onConfigurationChanged() {
        loadColors()
        invalidate()
    }

    private fun loadColors() {
        idleColor = context.getColor(R.color.bubble_idle)
        controlColor = context.getColor(R.color.bubble_control)
        successColor = context.getColor(R.color.bubble_success)
        failureColor = context.getColor(R.color.bubble_failure)
        val controlIcon = context.getColor(R.color.bubble_control_icon)
        spinnerPaint.color = controlIcon
        close.setTint(controlIcon)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx + shadowRoomPx)
    }

    override fun onDraw(canvas: Canvas) {
        val center = sizePx / 2f
        // The new colour grows from the centre over the old one, so no blended (muddy) colour ever shows.
        if (t < 1f) {
            fillPaint.color = fill(from)
            canvas.drawCircle(center, center, radius, fillPaint)
        }
        fillPaint.color = fill(to)
        // Done at t = 0.8, so the old colour's last thin ring closes at speed instead of lingering as an outline in
        // the curve's slow tail.
        canvas.drawCircle(center, center, radius * (t / REVEAL_END).coerceAtMost(1f), fillPaint)
        // The old content shrinks and fades out, the new grows and fades in; the ✕ turns in from -90° and out to 90°.
        // from != to while t < 1: one drawable drawn twice per frame would use its last alpha for both.
        if (t < 1f) drawContent(canvas, from, 1 - t, 1 - 0.4f * t, 90 * t)
        drawContent(canvas, to, t, 0.6f + 0.4f * t, -90 * (1 - t))
    }

    private fun drawContent(canvas: Canvas, status: BubbleStatus, alpha: Float, scale: Float, degrees: Float) {
        val center = sizePx / 2f
        val alpha255 = (alpha * 255).roundToInt()
        canvas.withSave {
            scale(scale, scale, center, center)
            when (status) {
                BubbleStatus.WORKING -> {
                    val turn = spin.animatedValue as? Float ?: 0f
                    spinnerPaint.alpha = alpha255
                    drawArc(spinnerBounds, 360 * turn - 90, SPINNER_SWEEP, false, spinnerPaint)
                }
                BubbleStatus.LISTENING -> {
                    rotate(degrees, center, center)
                    close.alpha = alpha255
                    close.draw(this)
                }
                BubbleStatus.IDLE, BubbleStatus.SUCCESS, BubbleStatus.FAILURE -> {
                    val icon = when (status) {
                        BubbleStatus.IDLE -> mic
                        BubbleStatus.SUCCESS -> check
                        else -> exclamation
                    }
                    icon.alpha = alpha255
                    icon.draw(this)
                }
            }
        }
    }

    private fun fill(status: BubbleStatus) = when (status) {
        BubbleStatus.IDLE -> idleColor
        BubbleStatus.LISTENING, BubbleStatus.WORKING -> controlColor
        BubbleStatus.SUCCESS -> successColor
        BubbleStatus.FAILURE -> failureColor
    }

    private fun description(status: BubbleStatus) = when (status) {
        BubbleStatus.IDLE, BubbleStatus.SUCCESS, BubbleStatus.FAILURE -> R.string.bubble_start
        BubbleStatus.LISTENING -> R.string.bubble_cancel
        BubbleStatus.WORKING -> R.string.bubble_working
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimations()
    }

    override fun onDetachedFromWindow() {
        morph.end() // A re-attach draws the final state.
        spin.cancel()
        super.onDetachedFromWindow()
    }

    private fun updateAnimations() {
        val working = status == BubbleStatus.WORKING ||
            (morph.isRunning && (from == BubbleStatus.WORKING || to == BubbleStatus.WORKING))
        if (working && isAttachedToWindow) {
            if (!spin.isStarted) spin.start()
        } else {
            spin.cancel()
        }
    }

    /** [id] as an [ICON_DP] square centred in the view; mutated, so alpha and tint stay local to this view. */
    private fun icon(id: Int) = context.getDrawable(id)!!.mutate().apply {
        val half = (ICON_DP * density / 2).roundToInt()
        setBounds(sizePx / 2 - half, sizePx / 2 - half, sizePx / 2 + half, sizePx / 2 + half)
    }

    private companion object {
        const val RADIUS_DP = 20
        const val MARGIN_DP = 6
        const val SHADOW_ROOM_DP = 6
        const val ICON_DP = 20
        const val SPINNER_STROKE_DP = 2.5f
        const val SPINNER_SWEEP = 270f
        const val MORPH_MS = 250L
        const val REVEAL_END = 0.8f
    }
}
