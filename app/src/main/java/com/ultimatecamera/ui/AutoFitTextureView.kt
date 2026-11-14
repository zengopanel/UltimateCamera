package com.ultimatecamera.ui

import android.content.Context
import android.view.TextureView

/** TextureView that keeps a fixed aspect ratio (width:height as shown on screen). */
class AutoFitTextureView(context: Context) : TextureView(context) {
    private var ratioW = 0
    private var ratioH = 0

    fun setAspectRatio(w: Int, h: Int) {
        ratioW = w; ratioH = h
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        if (ratioW == 0 || ratioH == 0) {
            setMeasuredDimension(w, h)
        } else if (w * ratioH < h * ratioW) {
            setMeasuredDimension(w, w * ratioH / ratioW)
        } else {
            setMeasuredDimension(h * ratioW / ratioH, h)
        }
    }
}
