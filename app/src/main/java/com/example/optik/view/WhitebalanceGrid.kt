package com.example.optik.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

class WhitebalanceGrid @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Range from -9 to +9
    private val maxTint = 9
    private val cells = maxTint * 2

    // Current selection
    var tintAB = 0
        private set
    var tintGM = 0
        private set

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4DFFFFFF") // 30% white
        strokeWidth = 2f
    }
    
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF") // 50% white
        strokeWidth = 3f
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9800") // Orange
        style = Paint.Style.FILL
    }
    
    private val dotStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.CENTER
    }

    var onTintChangedListener: ((ab: Int, gm: Int) -> Unit)? = null

    fun setTint(ab: Int, gm: Int) {
        tintAB = ab.coerceIn(-maxTint, maxTint)
        tintGM = gm.coerceIn(-maxTint, maxTint)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        
        // Draw labels
        val padding = 60f
        val gridW = w - padding * 2
        val gridH = h - padding * 2
        val startX = padding
        val startY = padding

        val cellW = gridW / cells
        val cellH = gridH / cells

        // Draw cell background colors
        val cellPaint = Paint().apply { style = Paint.Style.FILL }
        for (i in 0 until cells) {
            for (j in 0 until cells) {
                val ab = (i + 0.5f - maxTint) / maxTint.toFloat()
                val gm = (maxTint - (j + 0.5f)) / maxTint.toFloat()
                
                val colorX = (ab + gm) * 0.707f
                val colorY = (-ab + gm) * 0.707f
                
                val r = (128f + colorX * 127f - colorY * 127f).coerceIn(0f, 255f).toInt()
                val g = (128f + colorX * 64f + colorY * 127f).coerceIn(0f, 255f).toInt()
                val b = (128f - colorX * 127f - colorY * 127f).coerceIn(0f, 255f).toInt()
                
                cellPaint.color = Color.rgb(r, g, b)
                
                val cx = startX + i * cellW
                val cy = startY + j * cellH
                canvas.drawRect(cx, cy, cx + cellW, cy + cellH, cellPaint)
            }
        }

        // Draw grid
        for (i in 0..cells) {
            val cx = startX + i * cellW
            val cy = startY + i * cellH
            
            // Vertical lines
            val pX = if (i == maxTint) axisPaint else gridPaint
            canvas.drawLine(cx, startY, cx, startY + gridH, pX)
            
            // Horizontal lines
            val pY = if (i == maxTint) axisPaint else gridPaint
            canvas.drawLine(startX, cy, startX + gridW, cy, pY)
        }

        // Draw Labels: G (top), M (bottom), B (left), A (right)
        val textOffset = 10f
        canvas.drawText("G", w / 2, startY - 20f, textPaint)
        canvas.drawText("M", w / 2, startY + gridH + 40f, textPaint)
        
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("B", startX - 20f, h / 2 + textOffset, textPaint)
        
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("A", startX + gridW + 20f, h / 2 + textOffset, textPaint)
        
        // Reset text align
        textPaint.textAlign = Paint.Align.CENTER

        // Draw Dot
        val dotX = startX + (tintAB + maxTint) * cellW
        val dotY = startY + (maxTint - tintGM) * cellH
        val dotRadius = 16f
        
        canvas.drawCircle(dotX, dotY, dotRadius, dotPaint)
        canvas.drawCircle(dotX, dotY, dotRadius, dotStrokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            val padding = 60f
            val gridW = width - padding * 2
            val gridH = height - padding * 2
            val cellW = gridW / cells
            val cellH = gridH / cells

            val x = event.x - padding
            val y = event.y - padding

            var ab = (x / cellW).roundToInt() - maxTint
            var gm = maxTint - (y / cellH).roundToInt()

            ab = ab.coerceIn(-maxTint, maxTint)
            gm = gm.coerceIn(-maxTint, maxTint)

            if (ab != tintAB || gm != tintGM) {
                tintAB = ab
                tintGM = gm
                invalidate()
                onTintChangedListener?.invoke(tintAB, tintGM)
            }
            return true
        }
        return super.onTouchEvent(event)
    }
}
