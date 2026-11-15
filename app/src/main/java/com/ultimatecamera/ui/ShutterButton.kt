package com.ultimatecamera.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.min

/** Classic ring + inner disc shutter with press/release spring animation. */
class ShutterButton(context: Context) : View(context) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var inner = 1f
    private var anim: ValueAnimator? = null
    private var down = false
    private var longPressTriggered = false
    var videoMode = false
        set(value) {
            field = value
            invalidate()
        }
    var recording = false
        set(value) {
            field = value
            invalidate()
        }
    private val longPress = Runnable {
        if (down) longPressTriggered = performLongClick()
    }

    private fun animateInner(to: Float, duration: Long, spring: Boolean) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(inner, to).apply {
            this.duration = duration
            interpolator = if (spring) OvershootInterpolator(2.2f) else DecelerateInterpolator()
            addUpdateListener {
                inner = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        ring.strokeWidth = dp(4f)
        fill.color = if (videoMode) Theme.RECORD else Color.WHITE
        canvas.drawCircle(cx, cy, r - ring.strokeWidth / 2f, ring)
        if (recording) {
            val half = dp(12f) * inner
            val rect = android.graphics.RectF(cx - half, cy - half, cx + half, cy + half)
            canvas.drawRoundRect(rect, dp(4f), dp(4f), fill)
        } else {
            val innerRadius = if (videoMode) r - dp(13f) else r - dp(10f)
            canvas.drawCircle(cx, cy, innerRadius * inner, fill)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down = true
                longPressTriggered = false
                animateInner(0.84f, 90, false)
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_UP -> if (down) {
                removeCallbacks(longPress)
                down = false
                animateInner(1f, 280, true)
                if (!longPressTriggered && e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                down = false
                animateInner(1f, 160, false)
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
