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
import android.view.View
import android.view.ViewOutlineProvider
import dev.jamius.iris.R
import kotlin.math.roundToInt

/**
 * The mic bubble: a filled circle with a mic icon. While [listening] it turns red and emits a pulsing ring.
 * The view is larger than the circle by [RING_DP] on each side to leave room for the ring.
 */
internal class BubbleView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val radius = RADIUS_DP * density
    private val ringWidth = RING_DP * density
    val sizePx = (2 * (radius + ringWidth)).roundToInt()

    private val idleColor = context.getColor(R.color.bubble_idle)
    private val listeningColor = context.getColor(R.color.bubble_listening)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = context.getDrawable(R.drawable.ic_mic)!!.apply {
        val half = (ICON_DP * density / 2).roundToInt()
        setBounds(sizePx / 2 - half, sizePx / 2 - half, sizePx / 2 + half, sizePx / 2 + half)
    }

    private val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { invalidate() }
    }

    var listening = false
        set(value) {
            if (field == value) return
            field = value
            updatePulse()
            invalidate()
        }

    init {
        contentDescription = context.getString(R.string.bubble_description)
        elevation = 6 * density
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
        if (listening) {
            val t = pulse.animatedValue as? Float ?: 0f
            ringPaint.color = listeningColor
            ringPaint.alpha = ((1 - t) * 160).roundToInt()
            canvas.drawCircle(center, center, radius + ringWidth * t, ringPaint)
        }
        fillPaint.color = if (listening) listeningColor else idleColor
        canvas.drawCircle(center, center, radius, fillPaint)
        icon.draw(canvas)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updatePulse()
    }

    override fun onDetachedFromWindow() {
        pulse.cancel()
        super.onDetachedFromWindow()
    }

    private fun updatePulse() {
        if (listening && isAttachedToWindow) {
            if (!pulse.isStarted) pulse.start()
        } else {
            pulse.cancel()
        }
    }

    private companion object {
        const val RADIUS_DP = 26
        const val RING_DP = 8
        const val ICON_DP = 26
    }
}
