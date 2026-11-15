package com.ultimatecamera.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/** Glass capsule with zoom stops. The highlight glides to the stop nearest to the live zoom. */
class ZoomBar(context: Context) : View(context) {
    private var stops: List<Float> = listOf(1f)
    private var zoom = 1f
    private var nearest = 0
    private var hx = -1f
    private var anim: ValueAnimator? = null
    var onPick: ((Float) -> Unit)? = null

    private val chip get() = dp(38f)
    private val gap get() = dp(4f)
    private val pad get() = dp(5f)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#80000000") }
    private val hi = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#33FFFFFF") }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Theme.MEDIUM
    }
    private val rect = RectF()

    private fun centerOf(i: Int) = pad + chip / 2f + i * (chip + gap)

    private fun nearestIdx(z: Float): Int {
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in stops.indices) {
            val d = abs(ln(z.coerceAtLeast(0.01f) / stops[i]))
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    fun setStops(s: List<Float>) {
        stops = if (s.isEmpty()) listOf(1f) else s
        nearest = nearestIdx(zoom)
        hx = centerOf(nearest)
        requestLayout()
        invalidate()
    }

    fun setZoom(z: Float) {
        zoom = z
        val n = nearestIdx(z)
        if (n != nearest) {
            nearest = n
            anim?.cancel()
            anim = ValueAnimator.ofFloat(hx, centerOf(n)).apply {
                duration = 220
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    hx = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = pad * 2 + stops.size * chip + (stops.size - 1) * gap
        setMeasuredDimension(w.toInt(), (chip + pad * 2).toInt())
    }

    private fun fmt(z: Float, selected: Boolean): String {
        val whole = abs(z - z.roundToInt()) < 0.05f
        val base = if (whole) "${z.roundToInt()}" else "%.1f".format(z)
        return if (selected) "$base×" else base
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        rect.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, bg)
        if (hx < 0f) hx = centerOf(nearest)
        canvas.drawCircle(hx, h / 2f, chip / 2f, hi)
        for (i in stops.indices) {
            val sel = i == nearest
            text.color = if (sel) Theme.ACCENT else Color.WHITE
            text.textSize = dp(if (sel) 13f else 12f)
            val label = fmt(if (sel) zoom else stops[i], sel)
            canvas.drawText(label, centerOf(i), h / 2f + text.textSize / 3f, text)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val idx = ((e.x - pad + gap / 2f) / (chip + gap)).toInt().coerceIn(0, stops.size - 1)
                onPick?.invoke(stops[idx])
                performClick()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()
}
