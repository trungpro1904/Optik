package com.example.optik.camera

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult

class GestureTracker(
    private val context: Context,
    private val onGestureResult: (GestureRecognizerResult?) -> Unit
) {
    private var gestureRecognizer: GestureRecognizer? = null

    init {
        start()
    }

    fun start() {
        if (gestureRecognizer == null) {
            setupGestureRecognizer()
        }
    }

    private fun setupGestureRecognizer() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("gesture_recognizer.task")
                .build()

            val optionsBuilder = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinHandDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setNumHands(2) // Allow 2 hands for zoom gestures
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result, _ ->
                    onGestureResult(result)
                }
                .setErrorListener { error ->
                    Log.e("GestureTracker", "MediaPipe Error: ${error.message}")
                }

            gestureRecognizer = GestureRecognizer.createFromOptions(context.applicationContext, optionsBuilder.build())
        } catch (e: Exception) {
            Log.e("GestureTracker", "Error setting up GestureRecognizer: ${e.message}")
        }
    }

    fun processImage(mpImage: MPImage, rotationDegrees: Int = 0, timestampMs: Long = SystemClock.uptimeMillis()) {
        try {
            val options = com.google.mediapipe.tasks.vision.core.ImageProcessingOptions.builder()
                .setRotationDegrees(rotationDegrees)
                .build()
            gestureRecognizer?.recognizeAsync(mpImage, options, timestampMs)
        } catch (e: Exception) {
            gestureRecognizer?.recognizeAsync(mpImage, timestampMs)
        }
    }

    fun close() {
        gestureRecognizer?.close()
        gestureRecognizer = null
    }
}
