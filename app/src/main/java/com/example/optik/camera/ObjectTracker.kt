package com.example.optik.camera

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions

class ObjectTracker(private val onResult: (List<DetectedObject>) -> Unit) {

    private var isProcessing = false

    private val options = ObjectDetectorOptions.Builder()
        .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
        .enableMultipleObjects()
        .build()

    private val objectDetector = ObjectDetection.getClient(options)

    fun processBitmap(bitmap: Bitmap, rotationDegrees: Int = 0) {
        if (isProcessing) {
            return
        }
        isProcessing = true

        val inputImage = try {
            InputImage.fromBitmap(bitmap, rotationDegrees)
        } catch (e: Exception) {
            Log.e("ObjectTracker", "Failed to build InputImage", e)
            isProcessing = false
            return
        }

        objectDetector.process(inputImage)
            .addOnSuccessListener { detectedObjects ->
                onResult(detectedObjects)
            }
            .addOnFailureListener { e ->
                Log.e("ObjectTracker", "Object tracking failed", e)
            }
            .addOnCompleteListener {
                isProcessing = false
            }
    }

    fun close() {
        objectDetector.close()
    }
}
