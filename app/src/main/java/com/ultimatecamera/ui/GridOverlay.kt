package com.ultimatecamera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Rule-of-thirds grid. */
class GridOverlay(context: Context) : View(context) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 255, 255, 255) }

    override fun onDraw(canvas: Canvas) {
        line.strokeWidth = dp(0.8f)
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..2) {
            canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, line)
            canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, line)
        }
    }
}
