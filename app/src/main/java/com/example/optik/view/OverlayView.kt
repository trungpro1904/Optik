package com.example.optik.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val aiBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFF8DC") // Vanilla / Milk color
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private var showGrid = true
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 100
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    // AI Target Box
    private var aiTargetBox: RectF? = null
    var hideAiBox = false

    fun setGridVisible(visible: Boolean) {
        showGrid = visible
        invalidate()
    }

    fun updateAiBox(rect: RectF?) {
        aiTargetBox = rect
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        drawGrid(canvas)

        // Draw AI Box
        if (!hideAiBox) {
            aiTargetBox?.let {
                canvas.drawRect(it, aiBoxPaint)
            }
        }
    }

    private fun drawGrid(canvas: Canvas) {
        if (!showGrid) return
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawLine(w / 3f, 0f, w / 3f, h, gridPaint)
        canvas.drawLine(2f * w / 3f, 0f, 2f * w / 3f, h, gridPaint)
        canvas.drawLine(0f, h / 3f, w, h / 3f, gridPaint)
        canvas.drawLine(0f, 2f * h / 3f, w, 2f * h / 3f, gridPaint)
    }
}
