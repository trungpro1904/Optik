package com.example.optik.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

class FaceTracker(
    private val context: Context,
    private val onFaceResult: (FaceLandmarkerResult?) -> Unit
) {
    private var faceLandmarker: FaceLandmarker? = null

    init {
        start()
    }

    fun start() {
        if (faceLandmarker == null) {
            setupFaceLandmarker()
        }
    }

    private fun setupFaceLandmarker() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build()

            val optionsBuilder = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setNumFaces(1)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result, _ ->
                    onFaceResult(result)
                }
                .setErrorListener { error ->
                    Log.e("FaceTracker", "MediaPipe Error: ${error.message}")
                }

            faceLandmarker = FaceLandmarker.createFromOptions(context.applicationContext, optionsBuilder.build())
        } catch (e: Exception) {
            Log.e("FaceTracker", "Error setting up FaceLandmarker: ${e.message}")
        }
    }

    fun processImage(mpImage: MPImage, rotationDegrees: Int = 0, timestampMs: Long = SystemClock.uptimeMillis()) {
        try {
            val options = com.google.mediapipe.tasks.vision.core.ImageProcessingOptions.builder()
                .setRotationDegrees(rotationDegrees)
                .build()
            faceLandmarker?.detectAsync(mpImage, options, timestampMs)
        } catch (e: Exception) {
            faceLandmarker?.detectAsync(mpImage, timestampMs)
        }
    }

    fun close() {
        faceLandmarker?.close()
        faceLandmarker = null
    }
}
