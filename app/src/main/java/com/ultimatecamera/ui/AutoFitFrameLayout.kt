package com.ultimatecamera.ui

import android.content.Context
import android.widget.FrameLayout

/** FrameLayout that keeps a fixed aspect ratio (width:height as shown on screen). */
class AutoFitFrameLayout(context: Context) : FrameLayout(context) {
    private var rw = 0
    private var rh = 0

    fun setAspectRatio(w: Int, h: Int) {
        if (w == rw && h == rh) return
        rw = w
        rh = h
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        var fw = w
        var fh = h
        if (rw > 0 && rh > 0) {
            if (w * rh < h * rw) fh = w * rh / rw else fw = h * rw / rh
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(fw, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(fh, MeasureSpec.EXACTLY)
        )
    }
}
