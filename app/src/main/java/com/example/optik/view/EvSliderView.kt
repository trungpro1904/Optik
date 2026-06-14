package com.example.optik.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

class EvSliderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var evValue: Float = 0f // -2.0 to +2.0
    private var onEvChangeListener: ((Float) -> Unit)? = null
    private var onCloseListener: (() -> Unit)? = null

    private val step = 1 / 3f
    private val minEv = -2f
    private val maxEv = 2f

    private val density = resources.displayMetrics.density
    
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 1.5f * density
    }

    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8C00") // accent_orange
        textSize = 14f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8C00")
        style = Paint.Style.FILL
    }

    private var lastX = 0f
    private var isDragging = false
    private val pixelsPerEv = 120f * density // Space between major units

    fun setOnEvChangeListener(listener: (Float) -> Unit) {
        onEvChangeListener = listener
    }

    fun setOnCloseListener(listener: () -> Unit) {
        onCloseListener = listener
    }

    fun setEvValue(value: Float) {
        evValue = value.coerceIn(minEv, maxEv)
        invalidate()
    }
    
    // Support Int if needed for backward compatibility
    fun setEvValue(value: Int) {
        setEvValue(value.toFloat())
    }

    fun getEvValue(): Float = evValue

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Draw Title "EV" with current value
        val formattedEv = if (evValue > 0.01f) "+%.1f".format(evValue) else if (evValue < -0.01f) "%.1f".format(evValue) else "0.0"
        val titleText = if (abs(evValue) < 0.01f) "EV 0.0" else "EV $formattedEv"
        canvas.drawText(titleText, w / 2, 25f * density, titlePaint)

        // 2. Draw Close Button "X" (Simple X)
        val xSize = 10f * density
        val xRight = w - 20f * density
        val xTop = 15f * density
        canvas.drawLine(xRight - xSize, xTop, xRight, xTop + xSize, tickPaint)
        canvas.drawLine(xRight, xTop, xRight - xSize, xTop + xSize, tickPaint)

        // 3. Draw Indicator (Orange Triangle)
        val centerX = w / 2
        val indicatorY = 35f * density
        val path = Path()
        path.moveTo(centerX, indicatorY + 8f * density)
        path.lineTo(centerX - 5f * density, indicatorY)
        path.lineTo(centerX + 5f * density, indicatorY)
        path.close()
        canvas.drawPath(path, indicatorPaint)

        // 4. Draw Scale
        val scaleCenterY = h * 0.7f
        
        var v = minEv
        while (v <= maxEv + 0.01f) {
            val x = centerX + (v - evValue) * pixelsPerEv
            
            if (x > -pixelsPerEv && x < w + pixelsPerEv) {
                val isMajor = abs(v - v.roundToInt()) < 0.01f
                val isSelected = abs(v - evValue) < 0.05f // small epsilon for float comparison
                
                if (isMajor) {
                    val paint = if (isSelected) accentPaint else textPaint
                    val absV = abs(v.roundToInt())
                    val label = when {
                        v <= minEv + 0.01f -> "- $absV"
                        v >= maxEv - 0.01f -> "$absV +"
                        else -> absV.toString()
                    }
                    canvas.drawText(label, x, scaleCenterY + 15f * density, paint)
                    canvas.drawLine(x, scaleCenterY - 5f * density, x, scaleCenterY - 15f * density, if (isSelected) indicatorPaint else tickPaint)
                } else {
                    // Intermediate tick
                    canvas.drawLine(x, scaleCenterY - 8f * density, x, scaleCenterY - 12f * density, if (isSelected) indicatorPaint else tickPaint)
                }
            }
            v += step
        }
        
        // 5. Side Arrows
        val arrowSize = 5f * density
        val arrowPadding = 15f * density
        val arrowY = scaleCenterY - 10f * density
        
        // Left
        path.reset()
        path.moveTo(arrowPadding, arrowY)
        path.lineTo(arrowPadding + arrowSize, arrowY - arrowSize/1.5f)
        path.lineTo(arrowPadding + arrowSize, arrowY + arrowSize/1.5f)
        path.close()
        canvas.drawPath(path, tickPaint)
        
        // Right
        path.reset()
        path.moveTo(w - arrowPadding, arrowY)
        path.lineTo(w - arrowPadding - arrowSize, arrowY - arrowSize/1.5f)
        path.lineTo(w - arrowPadding - arrowSize, arrowY + arrowSize/1.5f)
        path.close()
        canvas.drawPath(path, tickPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                isDragging = true
                
                // Close button check
                val xRight = width - 20f * density
                val xSize = 10f * density
                val touchSlop = 20f * density
                if (event.x > xRight - xSize - touchSlop && event.y < 40f * density) {
                    onCloseListener?.invoke()
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val dx = event.x - lastX
                    val evDiff = -dx / pixelsPerEv
                    evValue = (evValue + evDiff).coerceIn(minEv, maxEv)
                    lastX = event.x
                    onEvChangeListener?.invoke(evValue)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                // Snap to nearest 1/3
                evValue = (evValue / step).roundToInt() * step
                evValue = evValue.coerceIn(minEv, maxEv)
                onEvChangeListener?.invoke(evValue)
                invalidate()
            }
        }
        return true
    }
}
