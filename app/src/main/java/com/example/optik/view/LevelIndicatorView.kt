package com.example.optik.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

class LevelIndicatorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
        alpha = 150
    }

    private val centerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val colorGreen = Color.parseColor("#80FF00")
    private val colorWhite = Color.WHITE

    var isVertical = false
    var angle = 0f // Current tilt angle in degrees
        set(value) {
            field = value
            invalidate()
        }

    private val maxAngle = 15f // Angle that corresponds to the end of the line
    private val tolerance = 1.5f // Angle within which it turns green
    
    private val ringRadius = 24f
    private val indicatorRadius = 16f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val cx = width / 2f
        val cy = height / 2f
        
        // Draw center ring
        canvas.drawCircle(cx, cy, ringRadius, centerRingPaint)

        // Calculate offset based on angle
        // Clamp angle between -maxAngle and maxAngle
        val clampedAngle = angle.coerceIn(-maxAngle, maxAngle)
        val ratio = clampedAngle / maxAngle
        
        // Update color
        indicatorPaint.color = if (abs(angle) <= tolerance) colorGreen else colorWhite

        if (isVertical) {
            // Draw vertical line
            canvas.drawLine(cx, 0f, cx, cy - ringRadius, linePaint)
            canvas.drawLine(cx, cy + ringRadius, cx, height.toFloat(), linePaint)
            
            // Draw moving indicator
            val maxTravel = (height / 2f) - indicatorRadius
            val indicatorY = cy + (ratio * maxTravel)
            canvas.drawCircle(cx, indicatorY, indicatorRadius, indicatorPaint)
        } else {
            // Draw horizontal line
            canvas.drawLine(0f, cy, cx - ringRadius, cy, linePaint)
            canvas.drawLine(cx + ringRadius, cy, width.toFloat(), cy, linePaint)
            
            // Draw moving indicator
            val maxTravel = (width / 2f) - indicatorRadius
            val indicatorX = cx + (ratio * maxTravel)
            canvas.drawCircle(indicatorX, cy, indicatorRadius, indicatorPaint)
        }
    }
}
