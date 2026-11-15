package com.ultimatecamera.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

object Theme {
    val ACCENT = Color.parseColor("#E2C58A")
    val ACCENT_DEEP = Color.parseColor("#A98548")
    val GLASS = Color.parseColor("#CC151514")
    val TEXT_DIM = Color.parseColor("#B4B0A8")
    val RECORD = Color.parseColor("#FF4D58")
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun View.dp(v: Float): Float = context.dp(v)
fun View.dpi(v: Int): Int = context.dpi(v)

/** Subtle scale-down on touch with a springy release. Does not consume the touch. */
@SuppressLint("ClickableViewAccessibility")
fun View.addPressFeedback(scale: Float = 0.88f) {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                v.animate().scaleX(scale).scaleY(scale).setDuration(90)
                    .setInterpolator(DecelerateInterpolator()).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(240)
                    .setInterpolator(OvershootInterpolator(2.5f)).start()
        }
        false
    }
}
