package com.ultimatecamera.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Horizontal ruler like a pro camera dial: drag or fling, ticks snap under the centre marker. */
class RulerSlider(context: Context) : View(context) {
    private var labels: List<String> = emptyList()
    private var pos = 0f
    private var spacing = dp(40f)
    private var lastIndex = -1
    private var anim: ValueAnimator? = null
    private var tracker: VelocityTracker? = null
    private var downX = 0f
    private var downPos = 0f
    var onSelect: ((Int) -> Unit)? = null

    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Theme.MEDIUM
    }

    fun setItems(items: List<String>, selected: Int, spacingDp: Float) {
        anim?.cancel()
        labels = items
        spacing = dp(spacingDp)
        pos = selected.toFloat().coerceIn(0f, max(0, items.size - 1).toFloat())
        lastIndex = pos.roundToInt()
        alpha = 0f
        animate().alpha(1f).setDuration(180).start()
        invalidate()
    }

    private fun setPos(p: Float) {
        pos = p.coerceIn(0f, max(0, labels.size - 1).toFloat())
        invalidate()
        val idx = pos.roundToInt()
        if (idx != lastIndex) {
            lastIndex = idx
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onSelect?.invoke(idx)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val n = labels.size
        if (n == 0) return
        val cx = width / 2f
        val every = max(1, ceil(dp(52f) / spacing).toInt())
        val first = max(0, floor(pos - cx / spacing).toInt())
        val last = min(n - 1, ceil(pos + cx / spacing).toInt())
        text.textSize = dp(11f)
        tick.strokeWidth = dp(1.5f)
        val top = dp(8f)
        for (i in first..last) {
            val x = cx + (i - pos) * spacing
            val dist = abs(x - cx) / cx
            val a = (255 * (1f - dist * 0.85f)).toInt().coerceIn(40, 255)
            val major = i % every == 0
            val th = if (major) dp(18f) else dp(10f)
            tick.color = Color.argb(a, 255, 255, 255)
            canvas.drawLine(x, top, x, top + th, tick)
            if (major) {
                val near = abs(i - pos) < 0.5f
                text.color = if (near) Theme.ACCENT else Color.argb(a, 255, 255, 255)
                canvas.drawText(labels[i], x, top + th + dp(15f), text)
            }
        }
        tick.color = Theme.ACCENT
        tick.strokeWidth = dp(2.5f)
        canvas.drawLine(cx, dp(4f), cx, dp(34f), tick)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (labels.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anim?.cancel()
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x
                downPos = pos
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                setPos(downPos - (e.x - downX) / spacing)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val t = tracker
                var vx = 0f
                if (t != null) {
                    t.computeCurrentVelocity(1000)
                    vx = t.xVelocity
                    t.recycle()
                }
                tracker = null
                val target = (pos - vx / spacing * 0.18f).roundToInt()
                    .coerceIn(0, labels.size - 1).toFloat()
                anim = ValueAnimator.ofFloat(pos, target).apply {
                    duration = (140 + abs(target - pos) * 9).toLong().coerceAtMost(520)
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { setPos(it.animatedValue as Float) }
                    start()
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
