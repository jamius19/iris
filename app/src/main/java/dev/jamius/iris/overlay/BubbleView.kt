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
import dev.jamius.iris.R
import kotlin.math.roundToInt

/** What the bubble shows. */
enum class BubbleStatus {
    IDLE,

    /** Red, with a pulsing ring. */
    LISTENING,

    /** A spinner in place of the mic icon (finalizing, cleanup, insertion). */
    WORKING,

    /** Green, with a tick. */
    SUCCESS,

    /** Red, with an exclamation mark. */
    FAILURE,
}

/**
 * The mic bubble: a filled circle with an icon drawn per [status]. While listening it emits a pulsing ring.
 * The view is larger than the circle by [RING_DP] on each side to leave room for the ring.
 */
internal class BubbleView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val radius = RADIUS_DP * density
    private val ringWidth = RING_DP * density
    val sizePx = (2 * (radius + ringWidth)).roundToInt()

    /** Diameter of the visible circle, and its offset from the view's top/left edge (room for the ring). */
    val circlePx = (2 * radius).roundToInt()
    val circleInsetPx = (sizePx - circlePx) / 2

    private val idleColor = context.getColor(R.color.bubble_idle)
    private val listeningColor = context.getColor(R.color.bubble_listening)
    private val successColor = context.getColor(R.color.bubble_success)
    private val failureColor = context.getColor(R.color.bubble_failure)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mic = icon(R.drawable.ic_mic)
    private val check = icon(R.drawable.ic_check)
    private val exclamation = icon(R.drawable.ic_exclamation)

    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = SPINNER_STROKE_DP * density
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.white)
    }
    private val spinnerBounds = RectF().apply {
        val center = sizePx / 2f
        val r = (ICON_DP - SPINNER_STROKE_DP) * density / 2
        set(center - r, center - r, center + r, center + r)
    }

    private val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { invalidate() }
    }
    private val spin = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    var status = BubbleStatus.IDLE
        set(value) {
            if (field == value) return
            field = value
            updateAnimations()
            invalidate()
        }

    init {
        contentDescription = context.getString(R.string.bubble_description)
        elevation = 5 * density
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val center = sizePx / 2
                val r = radius.roundToInt()
                outline.setOval(center - r, center - r, center + r, center + r)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    override fun onDraw(canvas: Canvas) {
        val center = sizePx / 2f
        if (status == BubbleStatus.LISTENING) {
            val t = pulse.animatedValue as? Float ?: 0f
            ringPaint.color = listeningColor
            ringPaint.alpha = ((1 - t) * 160).roundToInt()
            canvas.drawCircle(center, center, radius + ringWidth * t, ringPaint)
        }
        fillPaint.color = when (status) {
            BubbleStatus.IDLE, BubbleStatus.WORKING -> idleColor
            BubbleStatus.LISTENING -> listeningColor
            BubbleStatus.SUCCESS -> successColor
            BubbleStatus.FAILURE -> failureColor
        }
        canvas.drawCircle(center, center, radius, fillPaint)
        when (status) {
            BubbleStatus.IDLE, BubbleStatus.LISTENING -> mic.draw(canvas)
            BubbleStatus.WORKING -> {
                val t = spin.animatedValue as? Float ?: 0f
                canvas.drawArc(spinnerBounds, 360 * t - 90, SPINNER_SWEEP, false, spinnerPaint)
            }
            BubbleStatus.SUCCESS -> check.draw(canvas)
            BubbleStatus.FAILURE -> exclamation.draw(canvas)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimations()
    }

    override fun onDetachedFromWindow() {
        pulse.cancel()
        spin.cancel()
        super.onDetachedFromWindow()
    }

    private fun updateAnimations() {
        setRunning(pulse, status == BubbleStatus.LISTENING)
        setRunning(spin, status == BubbleStatus.WORKING)
    }

    private fun setRunning(animator: ValueAnimator, running: Boolean) {
        if (running && isAttachedToWindow) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
        }
    }

    /** [id] as an [ICON_DP] square centred in the view. */
    private fun icon(id: Int) = context.getDrawable(id)!!.apply {
        val half = (ICON_DP * density / 2).roundToInt()
        setBounds(sizePx / 2 - half, sizePx / 2 - half, sizePx / 2 + half, sizePx / 2 + half)
    }

    private companion object {
        const val RADIUS_DP = 20
        const val RING_DP = 6
        const val ICON_DP = 20
        const val SPINNER_STROKE_DP = 2.5f
        const val SPINNER_SWEEP = 270f
    }
}
