package com.ultimatecamera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.text.TextPaint
import android.view.View
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min

class ProMonitorView(context: Context) : View(context) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val panel = RectF()
    private var histogram = IntArray(48)
    private var readout = ""
    private var storage = ""
    private var audio = 0
    private var roll = 0f
    var showHistogram = false
    var showLevel = false
    var showAudio = false

    fun setHistogram(values: IntArray) {
        histogram = values
        invalidate()
    }

    fun setReadout(value: String) {
        if (readout == value) return
        readout = value
        invalidate()
    }

    fun setStorage(value: String) {
        if (storage == value) return
        storage = value
        invalidate()
    }

    fun setAudioLevel(value: Int) {
        audio = value.coerceAtLeast(0)
        invalidate()
    }

    fun setRoll(degrees: Float) {
        roll = degrees
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (showHistogram) drawHistogram(canvas)
        if (showLevel) drawLevel(canvas)
        if (readout.isNotEmpty()) drawReadout(canvas)
        if (showAudio) drawAudio(canvas)
    }

    private fun drawHistogram(canvas: Canvas) {
        val left = dp(12f)
        val top = dp(12f)
        val width = min(dp(152f), this.width * 0.42f)
        val height = dp(76f)
        panel.set(left, top, left + width, top + height)
        paint.color = 0xB8000000.toInt()
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(panel, dp(8f), dp(8f), paint)

        paint.color = 0xCCFFFFFF.toInt()
        paint.textSize = dp(9f)
        paint.typeface = Theme.MEDIUM
        canvas.drawText("LUMA", left + dp(8f), top + dp(13f), paint)
        val graphTop = top + dp(20f)
        val graphHeight = height - dp(28f)
        val graphWidth = width - dp(16f)
        val maxBin = histogram.maxOrNull()?.coerceAtLeast(1) ?: 1
        val step = graphWidth / histogram.size
        paint.color = Theme.ACCENT
        for (i in histogram.indices) {
            val barHeight = histogram[i].toFloat() / maxBin * graphHeight
            canvas.drawRect(
                left + dp(8f) + i * step,
                graphTop + graphHeight - barHeight,
                left + dp(8f) + (i + 1) * step,
                graphTop + graphHeight,
                paint
            )
        }
    }

    private fun drawReadout(canvas: Canvas) {
        val left = dp(12f)
        val top = dp(136f)
        paint.color = 0xB8000000.toInt()
        paint.style = Paint.Style.FILL
        paint.textSize = dp(10f)
        paint.typeface = Theme.MEDIUM
        val row = listOf(readout, storage).filter { it.isNotBlank() }.joinToString("  ·  ")
        val shown = TextUtils.ellipsize(row, paint, (this.width - left - dp(24f)).coerceAtLeast(0f), TextUtils.TruncateAt.END)
        val width = paint.measureText(shown.toString()) + dp(16f)
        panel.set(left, top, left + width, top + dp(28f))
        canvas.drawRoundRect(panel, dp(8f), dp(8f), paint)
        paint.color = Color.WHITE
        canvas.drawText(shown.toString(), left + dp(8f), top + dp(18f), paint)
    }

    private fun drawLevel(canvas: Canvas) {
        val cx = this.width / 2f
        val cy = this.height * 0.38f
        val half = dp(46f)
        val levelColor = if (abs(roll) < 1.5f) Theme.ACCENT else Color.WHITE
        canvas.save()
        canvas.rotate(-roll, cx, cy)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = 0xB8000000.toInt()
        canvas.drawLine(cx - half, cy, cx + half, cy, paint)
        paint.color = levelColor
        canvas.drawLine(cx - half, cy, cx - dp(8f), cy, paint)
        canvas.drawLine(cx + dp(8f), cy, cx + half, cy, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, dp(3f), paint)
        canvas.restore()
    }

    private fun drawAudio(canvas: Canvas) {
        val left = width - dp(132f)
        val top = dp(12f)
        val meterWidth = dp(120f)
        paint.color = 0xB8000000.toInt()
        paint.style = Paint.Style.FILL
        panel.set(left, top, left + meterWidth, top + dp(28f))
        canvas.drawRoundRect(panel, dp(8f), dp(8f), paint)
        paint.color = 0xCCFFFFFF.toInt()
        paint.textSize = dp(9f)
        paint.typeface = Theme.MEDIUM
        canvas.drawText("MIC", left + dp(7f), top + dp(12f), paint)
        val db = if (audio > 0) (20f * log10(audio / 32767f)).coerceIn(-48f, 0f) else -48f
        val fraction = (db + 48f) / 48f
        val trackLeft = left + dp(30f)
        val trackTop = top + dp(8f)
        val trackWidth = meterWidth - dp(38f)
        panel.set(trackLeft, trackTop, trackLeft + trackWidth, trackTop + dp(11f))
        paint.color = 0x66FFFFFF
        canvas.drawRoundRect(panel, dp(4f), dp(4f), paint)
        panel.right = trackLeft + trackWidth * fraction
        paint.color = if (fraction > 0.9f) Theme.RECORD else Theme.ACCENT
        canvas.drawRoundRect(panel, dp(4f), dp(4f), paint)
    }
}

class FocusPeakingView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB7F36B.toInt()
        style = Paint.Style.FILL
    }
    private var points = FloatArray(0)

    fun setPoints(values: FloatArray) {
        points = values
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = dp(1.1f)
        var index = 0
        while (index + 1 < points.size) {
            canvas.drawCircle(points[index] * width, points[index + 1] * height, radius, paint)
            index += 2
        }
    }
}
