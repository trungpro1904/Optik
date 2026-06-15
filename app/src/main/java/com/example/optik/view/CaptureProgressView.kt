package com.example.optik.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class CaptureProgressView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density
        alpha = 100 // Mờ cho vòng nền
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
    }

    private var progress = 0f // 0 to 360
    private var animator: ValueAnimator? = null
    private val rectF = RectF()
    private val padding = 20f * resources.displayMetrics.density

    fun startProgress(durationMs: Long, onFinish: (() -> Unit)? = null) {
        visibility = View.VISIBLE
        animator?.cancel()
        
        // Đảm bảo có một khoảng thời gian tối thiểu để xem được vòng quay mượt (ví dụ 500ms)
        val animDuration = if (durationMs < 500) 500L else durationMs

        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = animDuration
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    visibility = View.GONE
                    progress = 0f
                    onFinish?.invoke()
                }
            })
            start()
        }
    }

    fun stopProgress() {
        animator?.cancel()
        visibility = View.GONE
        progress = 0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val cx = width / 2f
        val cy = height / 2f
        val radius = Math.min(cx, cy) - padding
        
        rectF.set(cx - radius, cy - radius, cx + radius, cy + radius)
        
        // Vẽ vòng nền
        canvas.drawCircle(cx, cy, radius, ringPaint)
        
        // Vẽ vòng tiến trình
        canvas.drawArc(rectF, -90f, progress, false, progressPaint)
        
        // Vẽ chữ ở giữa
        val textY = cy - ((textPaint.descent() + textPaint.ascent()) / 2)
        canvas.drawText("Đang chụp ảnh. Vui lòng đợi...", cx, textY, textPaint)
    }
}
