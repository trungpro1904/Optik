package com.example.optik.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class ModeWheelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#222222")
        style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        strokeWidth = 2f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 20f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val basicBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6C4BFF") // accent_purple
        style = Paint.Style.FILL
    }

    private var currentAngle = 0f // 0 is Manual, 45 is Basic
    private var lastTouchAngle = 0f
    
    var onModeSelected: ((Int) -> Unit)? = null // 0: Manual, 1: Basic
    
    private val radius = 500f * resources.displayMetrics.density
    private val centerX get() = width / 2f
    private val centerY get() = -radius + 200f * resources.displayMetrics.density // Adjust visible height

    fun setInitialMode(mode: Int) {
        currentAngle = if (mode == 0) 0f else -45f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw big circle
        canvas.drawCircle(centerX, centerY, radius, bgPaint)

        // Draw Manual
        drawModeText(canvas, "Manual", currentAngle, false)
        
        // Draw Divider
        val lineAngle = currentAngle + 22.5f
        val lineStartR = radius - 100f * resources.displayMetrics.density
        val lineEndR = radius - 10f * resources.displayMetrics.density
        val lx1 = centerX + lineStartR * sin(Math.toRadians(lineAngle.toDouble())).toFloat()
        val ly1 = centerY + lineStartR * cos(Math.toRadians(lineAngle.toDouble())).toFloat()
        val lx2 = centerX + lineEndR * sin(Math.toRadians(lineAngle.toDouble())).toFloat()
        val ly2 = centerY + lineEndR * cos(Math.toRadians(lineAngle.toDouble())).toFloat()
        canvas.drawLine(lx1, ly1, lx2, ly2, linePaint)

        // Draw Basic
        drawModeText(canvas, "Basic", currentAngle + 45f, true)
    }

    private fun drawModeText(canvas: Canvas, text: String, angle: Float, withBg: Boolean) {
        val textR = radius - 60f * resources.displayMetrics.density
        val x = centerX + textR * sin(Math.toRadians(angle.toDouble())).toFloat()
        val y = centerY + textR * cos(Math.toRadians(angle.toDouble())).toFloat()

        canvas.save()
        // Rotate text to be tangential or upright? 
        // In image 3, Manual is tilted. Basic is horizontal.
        // It looks like the text rotates WITH the wheel.
        canvas.rotate(-angle, x, y)
        
        if (withBg) {
            val rect = RectF(x - 50f * resources.displayMetrics.density, 
                             y - 25f * resources.displayMetrics.density,
                             x + 50f * resources.displayMetrics.density,
                             y + 15f * resources.displayMetrics.density)
            canvas.drawRoundRect(rect, 12f * resources.displayMetrics.density, 12f * resources.displayMetrics.density, basicBgPaint)
        }
        
        canvas.drawText(text, x, y + 8f * resources.displayMetrics.density, textPaint)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x - centerX
        val y = event.y - centerY
        val angle = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchAngle = angle
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = angle - lastTouchAngle
                currentAngle += delta
                lastTouchAngle = angle
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                // Snap to nearest mode
                if (currentAngle > -22.5f) {
                    // Manual (0)
                    animateAngle(0f)
                    onModeSelected?.invoke(0)
                } else {
                    // Basic (-45)
                    animateAngle(-45f)
                    onModeSelected?.invoke(1)
                }
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun animateAngle(target: Float) {
        val animator = android.animation.ValueAnimator.ofFloat(currentAngle, target)
        animator.duration = 200
        animator.addUpdateListener {
            currentAngle = it.animatedValue as Float
            invalidate()
        }
        animator.start()
    }
}
