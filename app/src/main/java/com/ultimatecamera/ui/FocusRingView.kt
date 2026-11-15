package com.ultimatecamera.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.sin

/** Tap-to-focus ring with an exposure (sun) slider that appears beside it. */
class FocusRingView(context: Context) : View(context) {
    private var cx = 0f
    private var cy = 0f
    private var scale = 1f
    private var evFrac: Float? = null
    private var evText = ""

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ACCENT }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Theme.MEDIUM
    }
    private val hide = Runnable { animate().alpha(0f).setDuration(300).start() }

    init {
        alpha = 0f
    }

    fun show(x: Float, y: Float, ev: Float?, evLabel: String) {
        cx = x
        cy = y
        evFrac = ev
        evText = evLabel
        keepAlive()
        ValueAnimator.ofFloat(1.5f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                scale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun updateEv(frac: Float, evLabel: String) {
        evFrac = frac
        evText = evLabel
        keepAlive()
        invalidate()
    }

    private fun keepAlive() {
        removeCallbacks(hide)
        animate().cancel()
        alpha = 1f
        postDelayed(hide, 1800)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = dp(34f) * scale
        ring.strokeWidth = dp(1.8f)
        canvas.drawCircle(cx, cy, r, ring)
        for (k in 0 until 4) {
            val a = Math.toRadians(k * 90.0)
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            canvas.drawLine(cx + (r - dp(7f)) * c, cy + (r - dp(7f)) * s, cx + r * c, cy + r * s, ring)
        }
        val f = evFrac ?: return
        val side = if (cx + r + dp(48f) > width) -1f else 1f
        val x = cx + side * (r + dp(24f))
        val half = dp(46f)
        ring.alpha = 150
        canvas.drawLine(x, cy - half, x, cy + half, ring)
        ring.alpha = 255
        canvas.drawCircle(x, cy + half - f * 2f * half, dp(6f), dot)
        label.textSize = dp(12f)
        canvas.drawText(evText, x, cy - half - dp(8f), label)
    }
}
