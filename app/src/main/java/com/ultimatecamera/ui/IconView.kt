package com.ultimatecamera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class IconType { FLASH, GRID, FLIP, INFO, GALLERY, HDR, STABILIZATION, TIMER, SETTINGS, AUDIO }

/** Small vector-style icons drawn with Canvas (no image assets needed). */
class IconView(context: Context, var icon: IconType) : View(context) {

    var active: Boolean = false
        set(value) {
            field = value
            isSelected = value
            invalidate()
        }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val rect = RectF()

    init {
        addPressFeedback()
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height) * 0.46f
        val color = if (active) Theme.ACCENT else Color.WHITE
        stroke.color = color
        fill.color = color
        stroke.strokeWidth = dp(1.8f)
        canvas.save()
        canvas.translate((width - s) / 2f, (height - s) / 2f)
        when (icon) {
            IconType.FLASH -> {
                path.reset()
                path.moveTo(0.60f * s, 0f)
                path.lineTo(0.15f * s, 0.55f * s)
                path.lineTo(0.47f * s, 0.55f * s)
                path.lineTo(0.38f * s, s)
                path.lineTo(0.85f * s, 0.40f * s)
                path.lineTo(0.53f * s, 0.40f * s)
                path.close()
                if (active) canvas.drawPath(path, fill) else {
                    canvas.drawPath(path, stroke)
                    canvas.drawLine(0.05f * s, 0.05f * s, 0.95f * s, 0.98f * s, stroke)
                }
            }
            IconType.GRID -> {
                rect.set(0f, 0f, s, s)
                canvas.drawRoundRect(rect, 0.14f * s, 0.14f * s, stroke)
                canvas.drawLine(s / 3f, 0f, s / 3f, s, stroke)
                canvas.drawLine(2f * s / 3f, 0f, 2f * s / 3f, s, stroke)
                canvas.drawLine(0f, s / 3f, s, s / 3f, stroke)
                canvas.drawLine(0f, 2f * s / 3f, s, 2f * s / 3f, stroke)
            }
            IconType.FLIP -> {
                rect.set(0.05f * s, 0.05f * s, 0.95f * s, 0.95f * s)
                canvas.drawArc(rect, 200f, 140f, false, stroke)
                canvas.drawArc(rect, 20f, 140f, false, stroke)
                arrow(canvas, s / 2f, s / 2f, s * 0.45f, 340f, s * 0.14f)
                arrow(canvas, s / 2f, s / 2f, s * 0.45f, 160f, s * 0.14f)
            }
            IconType.INFO -> {
                canvas.drawCircle(s / 2f, s / 2f, 0.48f * s, stroke)
                canvas.drawCircle(s / 2f, 0.30f * s, 0.05f * s, fill)
                canvas.drawLine(s / 2f, 0.45f * s, s / 2f, 0.74f * s, stroke)
            }
            IconType.GALLERY -> {
                rect.set(0f, 0.08f * s, s, 0.92f * s)
                canvas.drawRoundRect(rect, 0.12f * s, 0.12f * s, stroke)
                canvas.drawCircle(0.30f * s, 0.34f * s, 0.07f * s, fill)
                path.reset()
                path.moveTo(0.06f * s, 0.84f * s)
                path.lineTo(0.38f * s, 0.54f * s)
                path.lineTo(0.60f * s, 0.74f * s)
                path.lineTo(0.72f * s, 0.62f * s)
                path.lineTo(0.94f * s, 0.84f * s)
                canvas.drawPath(path, stroke)
            }
            IconType.HDR -> {
                stroke.strokeWidth = dp(1.5f)
                rect.set(0.06f * s, 0.18f * s, 0.94f * s, 0.82f * s)
                canvas.drawRoundRect(rect, 0.12f * s, 0.12f * s, stroke)
                val oldSize = fill.textSize
                fill.textSize = 0.28f * s
                fill.typeface = Theme.MEDIUM
                fill.textAlign = Paint.Align.CENTER
                canvas.drawText("HDR", s / 2f, 0.62f * s, fill)
                fill.textSize = oldSize
            }
            IconType.STABILIZATION -> {
                rect.set(0.12f * s, 0.20f * s, 0.88f * s, 0.80f * s)
                canvas.drawRoundRect(rect, 0.14f * s, 0.14f * s, stroke)
                canvas.drawCircle(s / 2f, s / 2f, 0.17f * s, stroke)
                canvas.drawCircle(s / 2f, s / 2f, 0.045f * s, fill)
                canvas.drawLine(0.36f * s, 0.10f * s, 0.64f * s, 0.10f * s, stroke)
            }
            IconType.TIMER -> {
                canvas.drawCircle(s / 2f, 0.55f * s, 0.35f * s, stroke)
                canvas.drawLine(s / 2f, 0.20f * s, s / 2f, 0.10f * s, stroke)
                canvas.drawLine(0.38f * s, 0.08f * s, 0.62f * s, 0.08f * s, stroke)
                canvas.drawLine(s / 2f, 0.55f * s, s / 2f, 0.34f * s, stroke)
                canvas.drawLine(s / 2f, 0.55f * s, 0.67f * s, 0.64f * s, stroke)
            }
            IconType.SETTINGS -> {
                canvas.drawCircle(s / 2f, s / 2f, 0.31f * s, stroke)
                canvas.drawCircle(s / 2f, s / 2f, 0.11f * s, stroke)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val x1 = s / 2f + cos(a).toFloat() * 0.36f * s
                    val y1 = s / 2f + sin(a).toFloat() * 0.36f * s
                    val x2 = s / 2f + cos(a).toFloat() * 0.46f * s
                    val y2 = s / 2f + sin(a).toFloat() * 0.46f * s
                    canvas.drawLine(x1, y1, x2, y2, stroke)
                }
            }
            IconType.AUDIO -> {
                val bars = floatArrayOf(0.26f, 0.48f, 0.76f, 0.48f, 0.62f)
                for (i in bars.indices) {
                    val x = (0.18f + i * 0.16f) * s
                    canvas.drawLine(x, s * (0.5f - bars[i] * 0.42f), x, s * (0.5f + bars[i] * 0.42f), stroke)
                }
            }
        }
        canvas.restore()
    }

    private fun arrow(c: Canvas, cx: Float, cy: Float, r: Float, deg: Float, h: Float) {
        val a = Math.toRadians(deg.toDouble())
        val px = cx + r * cos(a).toFloat()
        val py = cy + r * sin(a).toFloat()
        val tx = -sin(a).toFloat()
        val ty = cos(a).toFloat()
        val nx = cos(a).toFloat()
        val ny = sin(a).toFloat()
        path.reset()
        path.moveTo(px + tx * h * 1.3f, py + ty * h * 1.3f)
        path.lineTo(px + nx * h, py + ny * h)
        path.lineTo(px - nx * h, py - ny * h)
        path.close()
        c.drawPath(path, fill)
    }
}
