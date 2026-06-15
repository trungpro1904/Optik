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

    private val handPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.FILL
    }

    private val handLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
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

    // Hand Landmarks
    private var handLandmarks: List<List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>>? = null

    fun setGridVisible(visible: Boolean) {
        showGrid = visible
        invalidate()
    }

    fun updateAiBox(rect: RectF?) {
        aiTargetBox = rect
        invalidate()
    }

    fun updateHandLandmarks(landmarks: List<List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>>?) {
        handLandmarks = landmarks
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

        drawHandLandmarks(canvas)
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

    private fun drawHandLandmarks(canvas: Canvas) {
        val landmarksList = handLandmarks ?: return
        val w = width.toFloat()
        val h = height.toFloat()

        // MediaPipe Hand connections
        val connections = listOf(
            // Thumb
            Pair(0, 1), Pair(1, 2), Pair(2, 3), Pair(3, 4),
            // Index finger
            Pair(0, 5), Pair(5, 6), Pair(6, 7), Pair(7, 8),
            // Middle finger
            Pair(5, 9), Pair(9, 10), Pair(10, 11), Pair(11, 12),
            // Ring finger
            Pair(9, 13), Pair(13, 14), Pair(14, 15), Pair(15, 16),
            // Pinky
            Pair(13, 17), Pair(0, 17), Pair(17, 18), Pair(18, 19), Pair(19, 20)
        )

        for (hand in landmarksList) {
            for (connection in connections) {
                val startPoint = hand[connection.first]
                val endPoint = hand[connection.second]
                canvas.drawLine(
                    startPoint.x() * w, startPoint.y() * h,
                    endPoint.x() * w, endPoint.y() * h,
                    handLinePaint
                )
            }
            for (landmark in hand) {
                canvas.drawCircle(landmark.x() * w, landmark.y() * h, 6f, handPointPaint)
            }
        }
    }
}
