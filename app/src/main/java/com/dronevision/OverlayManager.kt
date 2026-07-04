package com.dronevision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * A transparent, click-through window that sits on top of every app and draws
 * the detection boxes. Uses TYPE_APPLICATION_OVERLAY (requires the "Draw over
 * other apps" permission granted in MainActivity).
 */
class OverlayManager(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: OverlayView? = null

    fun show() {
        if (overlayView != null) return
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // FLAG_NOT_TOUCHABLE = clicks pass through to the drone app underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        overlayView = OverlayView(context)
        try {
            windowManager.addView(overlayView, params)
        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "addView failed", e)
            overlayView = null
        }
    }

    /** Push a new set of boxes to draw. Coordinates are in screen pixels. */
    fun update(detections: List<Detection>) {
        overlayView?.setDetections(detections)
    }

    fun hide() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) { }
        }
        overlayView = null
    }

    /** The actual drawing surface. */
    private class OverlayView(context: Context) : View(context) {

        private var detections: List<Detection> = emptyList()

        private val boxPaint = Paint().apply {
            color = Color.parseColor("#FF3B30")
            style = Paint.Style.STROKE
            strokeWidth = 6f
            isAntiAlias = true
        }
        private val bgPaint = Paint().apply {
            color = Color.parseColor("#CC000000")
            style = Paint.Style.FILL
        }
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 34f
            isAntiAlias = true
        }

        fun setDetections(d: List<Detection>) {
            detections = d
            postInvalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (d in detections) {
                canvas.drawRect(
                    d.left.toFloat(), d.top.toFloat(),
                    d.right.toFloat(), d.bottom.toFloat(), boxPaint
                )
                val label = "${d.label}  ${(d.confidence * 100).toInt()}%"
                val tw = textPaint.measureText(label)
                val ty = (d.top - 12).coerceAtLeast(38).toFloat()
                canvas.drawRect(
                    d.left.toFloat(), ty - 34, d.left + tw + 16, ty + 8, bgPaint
                )
                canvas.drawText(label, d.left + 8f, ty, textPaint)
            }
        }
    }
}
