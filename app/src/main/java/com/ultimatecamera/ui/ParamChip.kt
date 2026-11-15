package com.ultimatecamera.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Pro-panel parameter tab: small label over a big value. */
class ParamChip(context: Context, label: String) : LinearLayout(context) {
    private val labelTv = TextView(context)
    private val valueTv = TextView(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dpi(6), dpi(6), dpi(6), dpi(6))
        labelTv.apply {
            text = label
            textSize = 10f
            typeface = Theme.MEDIUM
            letterSpacing = 0.14f
            gravity = Gravity.CENTER
            setTextColor(Theme.TEXT_DIM)
        }
        valueTv.apply {
            textSize = 15f
            typeface = Theme.MEDIUM
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            text = "AUTO"
        }
        addView(labelTv)
        addView(valueTv)
        addPressFeedback(0.94f)
    }

    fun setValue(s: String) {
        valueTv.text = s
    }

    fun setChipSelected(on: Boolean) {
        labelTv.setTextColor(if (on) Theme.ACCENT else Theme.TEXT_DIM)
        valueTv.setTextColor(if (on) Theme.ACCENT else Color.WHITE)
    }
}
