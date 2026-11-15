package com.ultimatecamera.ui

import android.animation.ValueAnimator
import android.content.Context
import android.os.Bundle
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.accessibility.AccessibilityNodeInfo
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/** Text mode switcher (PHOTO / PRO) with a gliding accent dot. */
class ModeSelector(context: Context) : View(context) {
    private var items = listOf("PHOTO", "VIDEO", "PRO")
    private var selected = 0
    private var dotX = 0f
    private var anim: ValueAnimator? = null
    var onSelected: ((Int) -> Unit)? = null

    private var itemWidth = dp(76f)
    private val itemW get() = itemWidth

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Theme.MEDIUM
        letterSpacing = 0.16f
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ACCENT }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66111110 }
    private val selectedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x44332B1E }

    init {
        isFocusable = true
        isClickable = true
    }

    private fun centerOf(i: Int) = itemW * (i + 0.5f)

    fun setItems(labels: List<String>, selection: Int) {
        items = labels
        selected = selection.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        dotX = if (items.isEmpty()) 0f else centerOf(selected)
        requestLayout()
        invalidate()
    }

    fun selectedIndex(): Int = selected

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec).takeIf { it > 0 } ?: dpi(380)
        itemWidth = min(dp(76f), available.toFloat() / items.size.coerceAtLeast(1))
        setMeasuredDimension((itemW * items.size).toInt(), dpi(48))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        dotX = centerOf(selected)
    }

    fun select(i: Int, animate: Boolean) {
        if (items.isEmpty()) return
        val index = i.coerceIn(0, items.lastIndex)
        if (index == selected && dotX > 0f) return
        selected = index
        anim?.cancel()
        if (!animate) {
            dotX = centerOf(index)
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(dotX, centerOf(index)).apply {
            duration = 240
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                dotX = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty()) return
        val inset = dp(2f)
        val radius = dp(18f)
        canvas.drawRoundRect(inset, inset, width - inset, height - inset, radius, radius, track)
        val selectedLeft = centerOf(selected) - itemW / 2f + inset
        canvas.drawRoundRect(
            selectedLeft,
            inset,
            selectedLeft + itemW - inset * 2f,
            height - inset,
            radius,
            radius,
            selectedFill
        )
        text.textSize = dp(13f)
        val baseline = height * 0.50f + text.textSize / 3f
        for (i in items.indices) {
            text.color = if (i == selected) Theme.ACCENT else Theme.TEXT_DIM
            canvas.drawText(items[i], centerOf(i), baseline, text)
        }
        canvas.drawCircle(dotX, height - dp(5f), dp(2f), dot)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val i = (e.x / itemW).toInt().coerceIn(0, items.size - 1)
                if (i != selected) {
                    select(i, true)
                    onSelected?.invoke(i)
                }
                performClick()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
        info.isClickable = true
        info.contentDescription = items.getOrNull(selected)?.let { "Shooting mode, $it" } ?: "Shooting mode"
        info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (items.isEmpty()) return true
        val next = when (action) {
            AccessibilityNodeInfo.ACTION_CLICK,
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> (selected + 1).coerceAtMost(items.lastIndex)
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> (selected - 1).coerceAtLeast(0)
            else -> return super.performAccessibilityAction(action, arguments)
        }
        if (next != selected) {
            select(next, true)
            onSelected?.invoke(next)
            announceForAccessibility("${items[next]} mode")
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
