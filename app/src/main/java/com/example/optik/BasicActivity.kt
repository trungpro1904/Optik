package com.example.optik

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.OrientationEventListener
import android.view.TextureView
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import com.example.optik.camera.CameraManagerHelper
import com.example.optik.databinding.ActivityBasicBinding
import com.example.optik.view.LUTAdapter
import com.example.optik.camera.FaceTracker
import com.example.optik.settings.SettingsManager
import android.graphics.RectF
import android.util.Size
import com.google.mediapipe.framework.image.MediaImageBuilder

class BasicActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBasicBinding
    private lateinit var cameraHelper: CameraManagerHelper
    private lateinit var objectTracker: com.example.optik.camera.ObjectTracker
    private lateinit var faceTracker: FaceTracker
    private var gestureTracker: com.example.optik.camera.GestureTracker? = null
    private var orientationEventListener: OrientationEventListener? = null
    private var currentRotation: Float = 0f
    private var isFaceDetectionEnabled = false
    private var isCapturing = false
    private var isSwitchingMode = false
    private var expandAnimator: ObjectAnimator? = null
    private var levelSensorHelper: com.example.optik.camera.LevelSensorHelper? = null

    // Gesture State
    private var lastGestureActionTime = 0L
    private val GESTURE_COOLDOWN_MS = 3000L
    private var wasOpenPalm = false
    private var gestureCountdownRunning = false
    private var countdownSeconds = 0
    private var lastTwoHandDistance = -1f
    
    // Video Timer State
    private var isRecording = false
    private var imagesSavingCount = 0
    private var videoRecordingStartTime = 0L
    private val videoTimerHandler = Handler(Looper.getMainLooper())
    private val videoTimerRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val elapsed = System.currentTimeMillis() - videoRecordingStartTime
                val seconds = (elapsed / 1000).toInt()
                val h = seconds / 3600
                val m = (seconds % 3600) / 60
                val s = seconds % 60
                val timeStr = if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
                findViewById<android.widget.TextView>(R.id.tv_video_timer)?.text = timeStr
                videoTimerHandler.postDelayed(this, 500)
            }
        }
    }
    private var videoConfigs: List<CameraManagerHelper.VideoConfig> = emptyList()
    private var isFlashOn = false
    private var isTouchFocusLocked = false
    private var touchLockedFaceCenter: android.graphics.PointF? = null
    private var trackedObjectId: Int? = null
    private var isTrackingFace = false
    private var latestObjects: List<com.google.mlkit.vision.objects.DetectedObject> = emptyList()
    private var maxCameraMp = 12
    
    private lateinit var soundHelper: com.example.optik.camera.SoundHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsManager.getInstance(this).lastUsedMode = 0
        binding = ActivityBasicBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraHelper = CameraManagerHelper(this)
        soundHelper = com.example.optik.camera.SoundHelper(this)
        
        objectTracker = com.example.optik.camera.ObjectTracker { objects ->
            latestObjects = objects
            runOnUiThread {
                if (!isTouchFocusLocked || trackedObjectId == null || isTrackingFace) return@runOnUiThread
                
                val vw = binding.overlayView.width.toFloat()
                if (vw <= 0) return@runOnUiThread
                val scale = vw / 640f
                
                val trackedObj = objects.find { it.trackingId == trackedObjectId }
                if (trackedObj != null) {
                    val rect = trackedObj.boundingBox
                    val scaledRect = android.graphics.RectF(
                        rect.left * scale,
                        rect.top * scale,
                        rect.right * scale,
                        rect.bottom * scale
                    )
                    
                    touchLockedFaceCenter = android.graphics.PointF(scaledRect.centerX(), scaledRect.centerY())
                    val focusBox = findViewById<android.view.View>(R.id.focus_box_container)
                    focusBox.x = scaledRect.centerX() - focusBox.width / 2f
                    focusBox.y = scaledRect.centerY() - focusBox.height / 2f
                    
                    cameraHelper.updateFocusFromTracker(scaledRect, binding.previewArea.width, binding.previewArea.height)
                }
            }
        }
        
        faceTracker = FaceTracker(this) { result ->
            runOnUiThread {
                if (result == null || result.faceLandmarks().isEmpty()) {
                    if (!isTouchFocusLocked) {
                        binding.overlayView.updateAiBox(null)
                    }
                    return@runOnUiThread
                }
                
                var bestFace = result.faceLandmarks()[0]
                var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = Float.MIN_VALUE; var maxY = Float.MIN_VALUE
                
                var hasValidTrackedFace = false
                
                val center = touchLockedFaceCenter
                if (isTouchFocusLocked && center != null) {
                    var minDistance = Float.MAX_VALUE
                    for (face in result.faceLandmarks()) {
                        var cX = 0f; var cY = 0f
                        for (l in face) { cX += l.x(); cY += l.y() }
                        cX = (cX / face.size) * binding.overlayView.width
                        cY = (cY / face.size) * binding.overlayView.height
                        val dist = Math.hypot((cX - center.x).toDouble(), (cY - center.y).toDouble()).toFloat()
                        if (dist < minDistance) {
                            minDistance = dist
                            bestFace = face
                        }
                    }
                    if (minDistance < 200f) {
                        hasValidTrackedFace = true
                    }
                }
                
                if (isTouchFocusLocked && !hasValidTrackedFace) {
                    isTrackingFace = false
                    return@runOnUiThread
                }
                isTrackingFace = true

                for (l in bestFace) {
                    if (l.x() < minX) minX = l.x(); if (l.y() < minY) minY = l.y()
                    if (l.x() > maxX) maxX = l.x(); if (l.y() > maxY) maxY = l.y()
                }
                
                val vw = binding.overlayView.width.toFloat()
                val vh = binding.overlayView.height.toFloat()
                val faceRect = RectF(minX * vw, minY * vh, maxX * vw, maxY * vh)
                
                val isNear = (faceRect.width() / vw) > 0.25f
                val eyeLandmark = bestFace.getOrNull(159)
                
                val trackingRect = if (isNear && eyeLandmark != null) {
                    val ex = eyeLandmark.x() * vw
                    val ey = eyeLandmark.y() * vh
                    RectF(ex - 10f, ey - 10f, ex + 10f, ey + 10f)
                } else {
                    faceRect
                }
                
                if (isTouchFocusLocked) {
                    binding.overlayView.hideAiBox = true
                    touchLockedFaceCenter = android.graphics.PointF(trackingRect.centerX(), trackingRect.centerY())
                    val focusBox = findViewById<android.view.View>(R.id.focus_box_container)
                    focusBox.x = trackingRect.centerX() - focusBox.width / 2f
                    focusBox.y = trackingRect.centerY() - focusBox.height / 2f
                    cameraHelper.updateFocusFromTracker(trackingRect, binding.previewArea.width, binding.previewArea.height)
                } else {
                    binding.overlayView.hideAiBox = false
                    binding.overlayView.updateAiBox(trackingRect)
                    cameraHelper.updateFocusFromTracker(trackingRect, binding.previewArea.width, binding.previewArea.height)
                }
            }
        }
        
        gestureTracker = com.example.optik.camera.GestureTracker(this) { result ->
            runOnUiThread {
                if (result == null || result.gestures().isEmpty()) {
                    binding.overlayView.updateHandLandmarks(null)
                    wasOpenPalm = false
                    lastTwoHandDistance = -1f
                    return@runOnUiThread
                }
                
                binding.overlayView.updateHandLandmarks(result.landmarks())
                
                if (System.currentTimeMillis() - lastGestureActionTime < GESTURE_COOLDOWN_MS) {
                    return@runOnUiThread
                }
                
                val gestures = result.gestures()
                
                // Action 1: 1 hand Open Palm -> Closed Fist
                if (gestures.size == 1) {
                    val gestureName = gestures[0][0].categoryName()
                    if (gestureName == "Open_Palm") {
                        wasOpenPalm = true
                    } else if (gestureName == "Closed_Fist" && wasOpenPalm) {
                        wasOpenPalm = false
                        triggerGestureCapture()
                    }
                } else if (gestures.size == 2) {
                    // Action 2 & 3: 2 hands Open Palm -> Zoom
                    val gesture1 = gestures[0][0].categoryName()
                    val gesture2 = gestures[1][0].categoryName()
                    
                    if (gesture1 == "Open_Palm" && gesture2 == "Open_Palm") {
                        val hand1 = result.landmarks()[0]
                        val hand2 = result.landmarks()[1]
                        // Use WRIST (0) distance
                        val dx = hand1[0].x() - hand2[0].x()
                        val dy = hand1[0].y() - hand2[0].y()
                        val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                        
                        if (lastTwoHandDistance > 0) {
                            val diff = distance - lastTwoHandDistance
                            if (diff > 0.15f) {
                                // Moved apart -> Zoom In
                                switchNextLens(zoomIn = true)
                                lastGestureActionTime = System.currentTimeMillis()
                                lastTwoHandDistance = -1f
                            } else if (diff < -0.15f) {
                                // Moved closer -> Zoom Out
                                switchNextLens(zoomIn = false)
                                lastGestureActionTime = System.currentTimeMillis()
                                lastTwoHandDistance = -1f
                            } else {
                                lastTwoHandDistance = distance
                            }
                        } else {
                            lastTwoHandDistance = distance
                        }
                    } else {
                        lastTwoHandDistance = -1f
                    }
                }
            }
        }
        var lastAiProcessTime = 0L

        cameraHelper.onImageAvailable = { image ->
            val currentTime = android.os.SystemClock.uptimeMillis()
            if (currentTime - lastAiProcessTime > 100) {
                lastAiProcessTime = currentTime
                try {
                    if (binding.previewArea.width > 0) {
                        val h = (640f * binding.previewArea.height / binding.previewArea.width).toInt()
                        val bitmap = binding.previewArea.getBitmap(640, h)
                        if (bitmap != null) {
                            objectTracker.processBitmap(bitmap, currentRotation.toInt())
                            try {
                                val mpImage = com.google.mediapipe.framework.image.BitmapImageBuilder(bitmap).build()
                                val mpRotation = (360 - currentRotation.toInt()) % 360
                                faceTracker.processImage(mpImage, mpRotation, android.os.SystemClock.uptimeMillis())
                                gestureTracker?.processImage(mpImage, mpRotation, android.os.SystemClock.uptimeMillis())
                            } catch (e: Exception) {}
                        }
                    }
                } catch (e: Exception) {}
            }
        }

        val levelPitch = findViewById<com.example.optik.view.LevelIndicatorView>(R.id.level_pitch)
        val levelRoll = findViewById<com.example.optik.view.LevelIndicatorView>(R.id.level_roll)
        if (levelPitch != null) levelPitch.isVertical = true
        if (levelRoll != null) levelRoll.isVertical = false
        
        levelSensorHelper = com.example.optik.camera.LevelSensorHelper(this) { pitch, roll ->
            levelPitch?.angle = pitch
            levelRoll?.angle = roll
        }

        setupUI()
        setupCamera()
        setupOrientationListener()
    }

    private var isVideoModeActive = false
    private fun isVideoMode(): Boolean = isVideoModeActive

    private fun switchToVideo() {
        if (!isVideoModeActive) {
            switchMode(true)
        }
    }

    private fun switchToPhoto() {
        if (isVideoModeActive) {
            switchMode(false)
        }
    }

    private fun switchMode(isVideo: Boolean) {
        if (isSwitchingMode) return
        isSwitchingMode = true
        isVideoModeActive = isVideo
        binding.previewBlurOverlay.visibility = View.VISIBLE
        binding.previewBlurOverlay.alpha = 0f
        binding.previewBlurOverlay.animate().alpha(1f).setDuration(200).start()
        updateModeUI(isVideo)
        cameraHelper.setExposureCompensation(0f)
        cameraHelper.closeCamera()
        binding.root.postDelayed({
            cameraHelper.openCamera(binding.previewArea)
            isSwitchingMode = false
            binding.previewBlurOverlay.animate().alpha(0f).setDuration(300).withEndAction { binding.previewBlurOverlay.visibility = View.GONE }.start()
        }, 400)
    }

    private fun updateModeUI(isVideo: Boolean) {
        val density = resources.displayMetrics.density
        val targetTransPhoto = -50f * density
        val targetTransVideo = 50f * density
        val target = if (isVideo) targetTransVideo else targetTransPhoto
        
        binding.videoPhotoSelector.animate().translationX(target).setDuration(200).setUpdateListener {
            val progress = (binding.videoPhotoSelector.translationX - targetTransVideo) / (targetTransPhoto - targetTransVideo)
            val clamped = Math.max(0f, Math.min(1f, progress))
            binding.btnPhotoMode.alpha = 0.5f + 0.5f * clamped
            binding.btnVideoMode.alpha = 1.0f - 0.5f * clamped
        }.start()

        binding.videoPhotoSelector.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        val btnVideo = binding.btnVideoMode; val btnPhoto = binding.btnPhotoMode
        val btnFps = binding.topBar.findViewById<TextView>(R.id.btn_fps); val btnRes = binding.topBar.findViewById<TextView>(R.id.btn_resolution)
        val settings = SettingsManager.getInstance(this)
        if (isVideo) {
            btnVideo.alpha = 1.0f; btnVideo.typeface = android.graphics.Typeface.DEFAULT_BOLD
            btnPhoto.alpha = 0.5f; btnPhoto.typeface = android.graphics.Typeface.DEFAULT
            btnFps?.visibility = View.VISIBLE; btnFps?.text = settings.videoFps.ifEmpty { "60" }; btnRes?.text = settings.videoFormat.ifEmpty { "4K" }.replace("p", "")
            
            binding.shutterBg.setBackgroundResource(R.drawable.bg_shutter_video)
            binding.tvRec.visibility = View.VISIBLE
        } else {
            btnPhoto.alpha = 1.0f; btnPhoto.typeface = android.graphics.Typeface.DEFAULT_BOLD
            btnVideo.alpha = 0.5f; btnVideo.typeface = android.graphics.Typeface.DEFAULT
            btnFps?.visibility = View.GONE; btnRes?.text = settings.photoResolution.ifEmpty { "12mp" }
            
            binding.shutterBg.setBackgroundResource(R.drawable.circle_white)
            binding.tvRec.visibility = View.GONE
            isRecording = false
            updateRecordingUI()
        }
    }

    private fun setupUI() {
        val lutNames = listOf("Gốc", "Backrooms", "Classic Chrome", "CINESTILL", "Kodacolor", "Light Skin", "Hail Mary", "Retro", "Saturation+", "Summer", "Fujifilm C400", "Leica Monochrome", "Gotham", "Call me by your name")
        val lutFiles = listOf(null, "backrooms.PNG", "classic_chrome.PNG", "cinestill.PNG", "kodacolor_100.PNG", "light_skin.PNG", "project_hail_mary.PNG", "retro_1.PNG", "saturation+.PNG", "summer.PNG", "fuji_c400.PNG", "leica_monochrome.PNG", "gotham.PNG", "call_me_by_your_name.PNG")
        val lutAdapter = LUTAdapter(lutNames, lutFiles) { selectedLut ->
            val index = lutNames.indexOf(selectedLut)
            if (index >= 0) {
                cameraHelper.setSelectedLut(lutFiles[index])
                val pos = index
                binding.lutSlider.smoothScrollToPosition(pos)
            }
        }
        lutAdapter.selectedPosition = 0
        binding.lutSlider.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.lutSlider.adapter = lutAdapter
        
        val snapHelper = LinearSnapHelper()
        binding.lutSlider.onFlingListener = null
        snapHelper.attachToRecyclerView(binding.lutSlider)

        binding.lutSlider.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val center = recyclerView.width / 2f
                var closestChild: View? = null
                var closestDist = Float.MAX_VALUE
                var closestPos = -1
                
                for (i in 0 until recyclerView.childCount) {
                    val child = recyclerView.getChildAt(i)
                    val childCenter = child.left + child.width / 2f
                    val dist = Math.abs(childCenter - center)
                    if (dist < closestDist) {
                        closestDist = dist
                        closestChild = child
                        closestPos = recyclerView.getChildAdapterPosition(child)
                    }
                    
                    val scale = 1.0f + 0.1f * Math.max(0f, 1f - dist / (center / 2f))
                    child.scaleX = Math.min(1.1f, scale)
                    child.scaleY = Math.min(1.1f, scale)
                }
                
                if (closestPos != -1 && closestPos != lutAdapter.selectedPosition) {
                    lutAdapter.selectedPosition = closestPos
                    
                    for (i in 0 until recyclerView.childCount) {
                        val child = recyclerView.getChildAt(i)
                        val pos = recyclerView.getChildAdapterPosition(child)
                        val tv = child.findViewById<TextView>(R.id.text_lut_name)
                        if (pos == closestPos) {
                            tv?.setTextColor(android.graphics.Color.parseColor("#FF9800"))
                        } else {
                            tv?.setTextColor(android.graphics.Color.BLACK)
                        }
                    }
                    
                    val settings = SettingsManager.getInstance(this@BasicActivity)
                    if (settings.isHapticEnabled) {
                        recyclerView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                    
                    cameraHelper.setSelectedLut(lutFiles[closestPos])
                }
            }
        })

        binding.btnMenu.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.btnAlbum.setOnClickListener {
            if (imagesSavingCount > 0) {
                val albumProgress = findViewById<android.view.View>(R.id.album_progress)
                albumProgress?.visibility = android.view.View.VISIBLE
                android.widget.Toast.makeText(this, "Đang xử lý ảnh...", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val uri = cameraHelper.getLatestMediaUri()
            if (uri != null) {
                val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "image/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                startActivity(intent)
            } else { startActivity(Intent(Intent.ACTION_PICK).apply { type = "image/*" }) }
        }

        binding.btnExpand.setOnClickListener {
            expandAnimator?.cancel()
            val isExpanded = binding.lutSlider.visibility == View.VISIBLE
            val targetRotation = if (isExpanded) 180f else 0f
            expandAnimator = ObjectAnimator.ofFloat(binding.btnExpand, View.ROTATION, binding.btnExpand.rotation, targetRotation).apply { duration = 300; start() }
            if (isExpanded) { binding.lutSlider.visibility = View.GONE; binding.videoPhotoSelectorContainer.visibility = View.GONE }
            else { binding.evSlider.visibility = View.GONE; binding.lutSlider.visibility = View.VISIBLE; binding.videoPhotoSelectorContainer.visibility = View.VISIBLE }
        }
        
        var startX = 0f
        var initialTranslation = 0f
        val density = resources.displayMetrics.density
        val targetTransPhoto = -50f * density
        val targetTransVideo = 50f * density
        
        fun updateModeAlpha(trans: Float) {
            val progress = (trans - targetTransVideo) / (targetTransPhoto - targetTransVideo)
            val clamped = Math.max(0f, Math.min(1f, progress))
            binding.btnPhotoMode.alpha = 0.5f + 0.5f * clamped
            binding.btnVideoMode.alpha = 1.0f - 0.5f * clamped
        }

        binding.videoPhotoSelectorContainer.setOnTouchListener { _, event ->
            if (isRecording) return@setOnTouchListener true
            val v = binding.videoPhotoSelector
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> { 
                    startX = event.rawX
                    initialTranslation = v.translationX
                    true 
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - startX
                    var newTrans = initialTranslation + deltaX
                    if (newTrans > targetTransVideo) newTrans = targetTransVideo
                    if (newTrans < targetTransPhoto) newTrans = targetTransPhoto
                    v.translationX = newTrans
                    updateModeAlpha(newTrans)
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val isPhoto = v.translationX < 0
                    val target = if (isPhoto) targetTransPhoto else targetTransVideo
                    v.animate().translationX(target).setDuration(200).setUpdateListener {
                        updateModeAlpha(v.translationX)
                    }.start()
                    
                    val newModePhoto = isPhoto
                    if (newModePhoto != !isVideoMode()) {
                        val settings = SettingsManager.getInstance(this)
                        if (settings.isHapticEnabled) {
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        switchMode(!newModePhoto)
                    }
                    true
                }
                else -> false
            }
        }
        
        updateModeUI(false)
        
        val btnMode = binding.topBar.findViewById<TextView>(R.id.btn_mode)
        btnMode?.setOnClickListener {
            val overlay = binding.modeWheelOverlay.root
            val wheel = binding.modeWheelOverlay.modeWheel
            overlay.visibility = View.VISIBLE
            wheel.setInitialMode(1)
            
            overlay.setOnClickListener { overlay.visibility = View.GONE }
            wheel.onModeSelected = { mode ->
                overlay.visibility = View.GONE
                if (mode == 0) {
                    SettingsManager.getInstance(this).lastUsedMode = 1
                    cameraHelper.closeCamera()
                    cameraHelper.stopBackgroundThread()
                    faceTracker.close()
                    startActivity(Intent(this, ManualActivity::class.java))
                    finish()
                }
            }
        }
        
        binding.topBar.findViewById<TextView>(R.id.btn_ratio)?.setOnClickListener { showRatioPopup() }
        binding.topBar.findViewById<TextView>(R.id.btn_resolution)?.setOnClickListener { showResolutionPopup() }
        updateAspectRatio(SettingsManager.getInstance(this).aspectRatio.ifEmpty { "4:3" })

        binding.topBar.findViewById<TextView>(R.id.btn_ev)?.setOnClickListener {
            if (binding.evSlider.visibility == View.VISIBLE) binding.evSlider.visibility = View.GONE
            else {
                binding.lutSlider.visibility = View.GONE; binding.videoPhotoSelectorContainer.visibility = View.GONE; binding.evSlider.visibility = View.VISIBLE
                expandAnimator?.cancel(); expandAnimator = ObjectAnimator.ofFloat(binding.btnExpand, View.ROTATION, binding.btnExpand.rotation, 180f).apply { duration = 300; start() }
            }
        }
        binding.evSlider.setOnEvChangeListener { ev -> cameraHelper.setExposureCompensation(ev) }
        binding.evSlider.setOnCloseListener { binding.evSlider.visibility = View.GONE }
    }

    private var currentSelectedLensBtn: android.widget.TextView? = null

    private fun setupZoomControls(lenses: List<com.example.optik.camera.CameraLens>) {
        val container = binding.lensSelectorContainer
        container.removeAllViews()
        if (lenses.isEmpty()) { container.visibility = View.GONE; return }
        container.visibility = View.VISIBLE
        val density = resources.displayMetrics.density
        val size = (36 * density).toInt(); val margin = (4 * density).toInt()
        for (lens in lenses) {
            val tv = TextView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply { setMargins(margin, margin, margin, margin) }
                gravity = android.view.Gravity.CENTER
                val formatted = String.format(java.util.Locale.US, "%.1f", lens.zoomRatio)
                text = if (formatted.endsWith(".0")) "${formatted.substringBefore(".")}x" else "${formatted}x"
                setTextColor(android.graphics.Color.WHITE); textSize = 12f; background = getDrawable(R.drawable.circle_transparent)
                setOnClickListener {
                    currentSelectedLensBtn?.background = getDrawable(R.drawable.circle_transparent)
                    currentSelectedLensBtn?.setTextColor(android.graphics.Color.WHITE)
                    currentSelectedLensBtn?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(200)?.start()
                    
                    background = getDrawable(R.drawable.circle_white)
                    setTextColor(android.graphics.Color.BLACK)
                    animate().scaleX(1.2f).scaleY(1.2f).setDuration(200).start()
                    
                    currentSelectedLensBtn = this
                    cameraHelper.switchLens(binding.previewArea, lens.id)
                }
            }
            container.addView(tv)
            if (lens.id == cameraHelper.currentCameraId || (currentSelectedLensBtn == null && lens.zoomRatio == 1.0f)) {
                currentSelectedLensBtn = tv
                tv.background = getDrawable(R.drawable.circle_white)
                tv.setTextColor(android.graphics.Color.BLACK)
                tv.scaleX = 1.2f
                tv.scaleY = 1.2f
            }
        }
    }

    private fun switchNextLens(zoomIn: Boolean) {
        val container = binding.lensSelectorContainer
        if (container.childCount <= 1) return
        val currentIndex = (0 until container.childCount).indexOfFirst {
            container.getChildAt(it) == currentSelectedLensBtn
        }
        if (currentIndex != -1) {
            val targetIndex = if (zoomIn) currentIndex + 1 else currentIndex - 1
            if (targetIndex in 0 until container.childCount) {
                container.getChildAt(targetIndex).performClick()
            }
        }
    }

    private fun triggerGestureCapture() {
        if (gestureCountdownRunning || isCapturing) return
        gestureCountdownRunning = true
        countdownSeconds = 2
        
        val countdownText = android.widget.TextView(this).apply {
            text = "$countdownSeconds"
            textSize = 100f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        (binding.root as android.view.ViewGroup).addView(countdownText)
        
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (countdownSeconds > 0) {
                    countdownText.text = "$countdownSeconds"
                    
                    // Play beep sound (urgent if <= 3s)
                    if (countdownSeconds <= 3) {
                        soundHelper.playBeep(2f)
                    } else {
                        soundHelper.playBeep(1f)
                    }
                    
                    // Flash effect
                    binding.previewBlurOverlay.visibility = android.view.View.VISIBLE
                    binding.previewBlurOverlay.setBackgroundColor(android.graphics.Color.WHITE)
                    binding.previewBlurOverlay.alpha = 0.5f
                    binding.previewBlurOverlay.animate().alpha(0f).setDuration(200).withEndAction { 
                        binding.previewBlurOverlay.visibility = android.view.View.GONE 
                        binding.previewBlurOverlay.setBackgroundColor(android.graphics.Color.parseColor("#A0000000"))
                    }.start()
                    
                    countdownSeconds--
                    handler.postDelayed(this, 1000)
                } else {
                    (binding.root as android.view.ViewGroup).removeView(countdownText)
                    gestureCountdownRunning = false
                    
                    binding.bottomPanel.findViewById<android.view.View>(R.id.btn_shutter)?.performClick()
                    lastGestureActionTime = System.currentTimeMillis()
                }
            }
        }
        handler.post(runnable)
    }

    private fun showResolutionPopup() {
        val btnRes = binding.topBar.findViewById<TextView>(R.id.btn_resolution) ?: return
        val btnFps = binding.topBar.findViewById<TextView>(R.id.btn_fps) ?: return
        val isVideo = isVideoMode()
        val popupView = layoutInflater.inflate(if (isVideo) R.layout.popup_video_settings else R.layout.popup_resolution, null)
        val popup = android.widget.PopupWindow(popupView, -2, -2, true)
        
        if (isVideo) {
            val res4k = popupView.findViewById<TextView>(R.id.res_4k)
            val resHd = popupView.findViewById<TextView>(R.id.res_hd)
            val res720 = popupView.findViewById<TextView>(R.id.res_720)
            val fps120 = popupView.findViewById<TextView>(R.id.fps_120)
            val fps60 = popupView.findViewById<TextView>(R.id.fps_60)
            val fps30 = popupView.findViewById<TextView>(R.id.fps_30)

            fun updateFpsOptions(width: Int, height: Int) {
                val config = videoConfigs.find { it.width == width && it.height == height }
                val maxFps = config?.maxFps ?: 30
                
                fps120?.visibility = if (maxFps >= 120) View.VISIBLE else View.GONE
                fps60?.visibility = if (maxFps >= 60) View.VISIBLE else View.GONE
                
                val currentFps = btnFps.text.toString().toIntOrNull() ?: 30
                if (currentFps > maxFps) {
                    val fallback = if (maxFps >= 60) 60 else 30
                    btnFps.text = fallback.toString()
                    com.example.optik.settings.SettingsManager.getInstance(this@BasicActivity).videoFps = fallback.toString()
                }

                // Cập nhật màu nhấn cho nút (chữ cam sáng, không chọn thì làm mờ 0.6)
                listOfNotNull(res4k, resHd, res720).forEach { it.alpha = 0.6f }
                if (width == 3840) res4k?.alpha = 1f
                if (width == 1920) resHd?.alpha = 1f
                if (width == 1280) res720?.alpha = 1f
                
                listOfNotNull(fps120, fps60, fps30).forEach { it.alpha = 0.6f }
                when (btnFps.text.toString()) {
                    "120" -> fps120?.alpha = 1f
                    "60" -> fps60?.alpha = 1f
                    "30" -> fps30?.alpha = 1f
                }
            }

            res4k?.visibility = if (videoConfigs.any { it.width == 3840 }) View.VISIBLE else View.GONE
            resHd?.visibility = if (videoConfigs.any { it.width == 1920 }) View.VISIBLE else View.GONE
            res720?.visibility = if (videoConfigs.any { it.width == 1280 }) View.VISIBLE else View.GONE

            res4k?.setOnClickListener { btnRes.text = "4K"; com.example.optik.settings.SettingsManager.getInstance(this).videoFormat = "4K"; updateFpsOptions(3840, 2160) }
            resHd?.setOnClickListener { btnRes.text = "HD"; com.example.optik.settings.SettingsManager.getInstance(this).videoFormat = "HD"; updateFpsOptions(1920, 1080) }
            res720?.setOnClickListener { btnRes.text = "720"; com.example.optik.settings.SettingsManager.getInstance(this).videoFormat = "720p"; updateFpsOptions(1280, 720) }
            
            fps120?.setOnClickListener { btnFps.text = "120"; com.example.optik.settings.SettingsManager.getInstance(this).videoFps = "120"; updateFpsOptions(if (btnRes.text == "4K") 3840 else if (btnRes.text == "HD") 1920 else 1280, if (btnRes.text == "4K") 2160 else if (btnRes.text == "HD") 1080 else 720) }
            fps60?.setOnClickListener { btnFps.text = "60"; com.example.optik.settings.SettingsManager.getInstance(this).videoFps = "60"; updateFpsOptions(if (btnRes.text == "4K") 3840 else if (btnRes.text == "HD") 1920 else 1280, if (btnRes.text == "4K") 2160 else if (btnRes.text == "HD") 1080 else 720) }
            fps30?.setOnClickListener { btnFps.text = "30"; com.example.optik.settings.SettingsManager.getInstance(this).videoFps = "30"; updateFpsOptions(if (btnRes.text == "4K") 3840 else if (btnRes.text == "HD") 1920 else 1280, if (btnRes.text == "4K") 2160 else if (btnRes.text == "HD") 1080 else 720) }
            
            val currentW = when(btnRes.text) { "4K" -> 3840; "HD" -> 1920; else -> 1280 }
            updateFpsOptions(currentW, if(currentW == 3840) 2160 else if(currentW == 1920) 1080 else 720)
        } else {
            val clickListener = View.OnClickListener { v -> 
                if (v is TextView) { 
                    btnRes.text = v.text; 
                    SettingsManager.getInstance(this).photoResolution = v.text.toString()
                    popup.dismiss() 
                } 
            }
            val v48 = popupView.findViewById<TextView>(R.id.res_48mp)
            val v24 = popupView.findViewById<TextView>(R.id.res_24mp)
            val v12 = popupView.findViewById<TextView>(R.id.res_12mp)
            
            v48?.visibility = if (maxCameraMp >= 48) View.VISIBLE else View.GONE
            v24?.visibility = if (maxCameraMp >= 24) View.VISIBLE else View.GONE
            v12?.visibility = View.VISIBLE
            
            listOfNotNull(v48, v24, v12).forEach { it.setOnClickListener(clickListener) }
        }
        popup.showAsDropDown(btnRes)
    }

    private fun showRatioPopup() {
        val btnRatio = binding.topBar.findViewById<TextView>(R.id.btn_ratio) ?: return
        val popupView = layoutInflater.inflate(R.layout.popup_ratio, null)
        val popup = android.widget.PopupWindow(popupView, -2, -2, true)
        val clickListener = View.OnClickListener { v -> 
            if (v is TextView) { 
                btnRatio.text = v.text
                val ratioStr = v.text.toString()
                SettingsManager.getInstance(this@BasicActivity).aspectRatio = ratioStr
                updateAspectRatio(ratioStr)
                cameraHelper.closeCamera()
                cameraHelper.openCamera(binding.previewArea)
                popup.dismiss() 
            } 
        }
        listOf(R.id.ratio_4_3, R.id.ratio_16_9, R.id.ratio_1_1).forEach { popupView.findViewById<TextView>(it).setOnClickListener(clickListener) }
        popup.showAsDropDown(btnRatio)
    }

    private fun updateAspectRatio(r: String) {
        val params = binding.previewContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        when (r) { "4:3" -> params.dimensionRatio = "3:4"; "16:9" -> params.dimensionRatio = "9:16"; "1:1" -> params.dimensionRatio = "1:1"; "Full" -> params.dimensionRatio = null }
        binding.previewContainer.layoutParams = params
    }

    private fun setupCamera() {
        binding.overlayView.setGridVisible(SettingsManager.getInstance(this).isGridEnabled)
        binding.previewArea.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { 
                binding.previewArea.postDelayed({
                    cameraHelper.openCamera(binding.previewArea) 
                }, 250)
            }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {
                cameraHelper.updateDisplaySurface(android.view.Surface(s))
            }
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(s: android.graphics.SurfaceTexture) {}
        }
        
        binding.previewArea.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                val x = event.x
                val y = event.y
                
                isTouchFocusLocked = true
                
                val focusBox = findViewById<android.view.View>(R.id.focus_box_container)
                focusBox.visibility = View.VISIBLE
                val focusBorder = findViewById<android.view.View>(R.id.focus_box_border)
                focusBorder?.setBackgroundResource(R.drawable.focus_border)
                focusBox.x = x - focusBox.width / 2f
                focusBox.y = y - focusBox.height / 2f
                
                cameraHelper.focusAtPoint(x, y, binding.previewArea.width, binding.previewArea.height, true)
                touchLockedFaceCenter = android.graphics.PointF(x, y)
                trackedObjectId = null
                
                val vw = binding.overlayView.width.toFloat()
                if (vw > 0) {
                    val scale = vw / 640f
                    for (obj in latestObjects) {
                        val rect = obj.boundingBox
                        val scaledRect = android.graphics.RectF(
                            rect.left * scale,
                            rect.top * scale,
                            rect.right * scale,
                            rect.bottom * scale
                        )
                        if (scaledRect.contains(x, y)) {
                            trackedObjectId = obj.trackingId
                            break
                        }
                    }
                }
            }
            true
        }
        
        cameraHelper.onFocusFinished = { success ->
            runOnUiThread {
                val focusBorder = findViewById<android.view.View>(R.id.focus_box_border)
                if (success) {
                    focusBorder?.setBackgroundResource(R.drawable.focus_border_success)
                } else {
                    isTouchFocusLocked = false
                    findViewById<android.view.View>(R.id.focus_box_container).visibility = View.GONE
                    binding.overlayView.hideAiBox = false
                    cameraHelper.cancelFocus()
                }
            }
        }
        
        findViewById<android.view.View>(R.id.btn_focus_cancel).setOnClickListener {
            isTouchFocusLocked = false
            findViewById<android.view.View>(R.id.focus_box_container).visibility = View.GONE
            binding.overlayView.hideAiBox = false
            cameraHelper.cancelFocus()
        }
        
        cameraHelper.onCameraInfoAvailable = { info ->
            videoConfigs = info.videoConfigs
        }

        cameraHelper.onImageSaving = {
            runOnUiThread {
                findViewById<android.widget.ProgressBar>(R.id.album_progress)?.visibility = View.VISIBLE
            }
        }
        
        cameraHelper.onThumbnailAvailable = { bitmap ->
            runOnUiThread {
                findViewById<android.widget.ImageView>(R.id.album_thumbnail)?.setImageBitmap(bitmap)
            }
        }
        
        cameraHelper.onPictureSaved = { success ->
            runOnUiThread {
                isCapturing = false
                findViewById<android.widget.ProgressBar>(R.id.album_progress)?.visibility = View.GONE
            }
        }

        cameraHelper.onResolutionsAvailable = { sizes -> 
            runOnUiThread { 
                maxCameraMp = sizes.maxOfOrNull { it.width * it.height / 1_000_000 } ?: 12
                val btnRes = binding.topBar.findViewById<TextView>(R.id.btn_resolution)
                if (btnRes != null && !isVideoMode()) {
                    btnRes.text = "${listOf(48, 24, 12).firstOrNull { it <= maxCameraMp } ?: 12}mp"
                }
            } 
        }
        
        binding.btnShutter.setOnClickListener {
            if (isVideoMode()) {
                isRecording = !isRecording
                if (isRecording) {
                    val resText = binding.topBar.findViewById<TextView>(R.id.btn_resolution).text.toString()
                    val fpsText = binding.topBar.findViewById<TextView>(R.id.btn_fps).text.toString()
                    val ratioText = binding.topBar.findViewById<TextView>(R.id.btn_ratio).text.toString()
                    
                    val width = when(resText) { "4K" -> 3840; "HD" -> 1920; else -> 1280 }
                    val height = when(resText) { "4K" -> 2160; "HD" -> 1080; else -> 720 }
                    
                    val finalSize = when(ratioText) {
                        "1:1" -> Size(height, height)
                        "16:9" -> Size(width, height)
                        else -> Size(width, (width * 3 / 4))
                    }
                    
                    val validConfig = videoConfigs.minByOrNull {
                        Math.abs(it.width - finalSize.width) + Math.abs(it.height - finalSize.height)
                    }
                    val validSize = if (validConfig != null) Size(validConfig.width, validConfig.height) else finalSize
                    
                    val fps = fpsText.toIntOrNull() ?: 30
                    if (!cameraHelper.startRecording(binding.previewArea, validSize, fps)) {
                        isRecording = false
                        android.widget.Toast.makeText(this@BasicActivity, "Failed to start recording", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        soundHelper.playRecStart()
                        
                        // Start timer
                        videoRecordingStartTime = System.currentTimeMillis()
                        val tvTimer = findViewById<android.widget.TextView>(R.id.tv_video_timer)
                        tvTimer?.visibility = View.VISIBLE
                        tvTimer?.text = "00:00"
                        videoTimerHandler.postDelayed(videoTimerRunnable, 500)
                    }
                } else {
                    soundHelper.playRecStop()
                    cameraHelper.stopRecording()
                    
                    // Stop timer
                    findViewById<android.widget.TextView>(R.id.tv_video_timer)?.visibility = View.GONE
                    videoTimerHandler.removeCallbacks(videoTimerRunnable)
                }
                updateRecordingUI()
            } else {
                if (isCapturing) return@setOnClickListener
                isCapturing = true
                soundHelper.playShutter()
                binding.previewBlurOverlay.visibility = View.VISIBLE; binding.previewBlurOverlay.setBackgroundColor(android.graphics.Color.BLACK); binding.previewBlurOverlay.alpha = 1f
                binding.previewBlurOverlay.animate().alpha(0f).setDuration(300).withEndAction { binding.previewBlurOverlay.visibility = View.GONE; binding.previewBlurOverlay.setBackgroundColor(android.graphics.Color.parseColor("#A0000000")) }.start()
                
                val currentShutterMs = cameraHelper.getCurrentShutterNs() / 1_000_000L
                if (currentShutterMs >= 125) {
                    val progressView = findViewById<com.example.optik.view.CaptureProgressView>(R.id.capture_progress_view)
                    progressView?.startProgress(currentShutterMs)
                }
                
                cameraHelper.captureImage()
            }
        }
        
        binding.bottomPanel.findViewById<View>(R.id.btn_switch)?.setOnClickListener { cameraHelper.toggleFrontBackCamera(binding.previewArea) }
        
        binding.bottomPanel.findViewById<android.view.View>(R.id.btn_flash).setOnClickListener {
            isFlashOn = !isFlashOn
            cameraHelper.toggleFlash(isFlashOn)
            (it as android.widget.ImageView).setImageResource(
                if (isFlashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
            )
        }
        
        cameraHelper.onCamerasAvailable = { runOnUiThread { setupZoomControls(it) } }
        
        cameraHelper.onCaptureStartedListener = { exposureTimeNs ->
            runOnUiThread {
                imagesSavingCount++
                val exposureMs = exposureTimeNs / 1_000_000
                if (exposureMs > 200) {
                    setUiEnabled(false)
                    val countdown = findViewById<com.google.android.material.progressindicator.CircularProgressIndicator>(R.id.exposure_countdown)
                    countdown?.visibility = View.VISIBLE
                    countdown?.max = 100
                    countdown?.progress = 0
                    val animator = android.animation.ValueAnimator.ofInt(0, 100)
                    animator.duration = exposureMs
                    animator.addUpdateListener { anim -> countdown?.progress = anim.animatedValue as Int }
                    animator.start()
                }
            }
        }
        
        cameraHelper.onCaptureFinishedListener = {
            runOnUiThread {
                if (imagesSavingCount > 0) imagesSavingCount--
                if (imagesSavingCount == 0) {
                    val albumProgress = findViewById<com.google.android.material.progressindicator.CircularProgressIndicator>(R.id.album_progress)
                    albumProgress?.visibility = View.GONE
                }
                val countdown = findViewById<com.google.android.material.progressindicator.CircularProgressIndicator>(R.id.exposure_countdown)
                countdown?.visibility = View.GONE
                setUiEnabled(true)
            }
        }
    }

    private fun setUiEnabled(isEnabled: Boolean) {
        val alphaVal = if (isEnabled) 1.0f else 0.4f
        val viewsToToggle = listOf(
            binding.btnMenu,
            binding.btnShutter,
            binding.topBar.findViewById(R.id.btn_resolution),
            binding.topBar.findViewById(R.id.btn_fps),
            binding.topBar.findViewById(R.id.btn_ev),
            binding.topBar.findViewById(R.id.btn_mode),
            binding.topBar.findViewById(R.id.btn_ratio),
            binding.bottomPanel.findViewById(R.id.btn_flash),
            binding.bottomPanel.findViewById(R.id.btn_switch),
            binding.btnAlbum,
            binding.btnExpand,
            binding.videoPhotoSelectorContainer
        )
        
        viewsToToggle.forEach { view ->
            view?.let {
                it.isEnabled = isEnabled
                it.alpha = alphaVal
            }
        }
        
        val lensContainer = binding.lensSelectorContainer
        for (i in 0 until lensContainer.childCount) {
            val child = lensContainer.getChildAt(i)
            child.isEnabled = isEnabled
            child.alpha = alphaVal
        }
    }

    private fun updateRecordingUI() {
        val shutterBg = binding.shutterBg.background
        if (shutterBg is android.graphics.drawable.GradientDrawable) {
            val color = if (isRecording) getColor(R.color.recording_red) else getColor(R.color.white)
            shutterBg.setStroke((5 * resources.displayMetrics.density).toInt(), color)
        }
        
        val isEnabled = !isRecording
        val alphaVal = if (isEnabled) 1.0f else 0.4f
        
        val viewsToToggle = listOf(
            binding.btnMenu,
            binding.topBar.findViewById(R.id.btn_resolution),
            binding.topBar.findViewById(R.id.btn_fps),
            binding.topBar.findViewById(R.id.btn_ev),
            binding.topBar.findViewById(R.id.btn_mode),
            binding.topBar.findViewById(R.id.btn_ratio),
            binding.bottomPanel.findViewById(R.id.btn_flash),
            binding.bottomPanel.findViewById(R.id.btn_switch),
            binding.btnAlbum,
            binding.btnExpand,
            binding.videoPhotoSelectorContainer
        )
        
        viewsToToggle.forEach { view ->
            view?.let {
                it.isEnabled = isEnabled
                it.alpha = alphaVal
            }
        }
        
        val lensContainer = binding.lensSelectorContainer
        for (i in 0 until lensContainer.childCount) {
            val child = lensContainer.getChildAt(i)
            child.isEnabled = isEnabled
            child.alpha = alphaVal
        }
    }

    private fun setupOrientationListener() {
        orientationEventListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val target = when (o) { in 45..134 -> 270f; in 135..224 -> 180f; in 225..314 -> 90f; else -> 0f }
                if (target != currentRotation) { rotateViews(target); currentRotation = target }
            }
        }
    }

    private fun rotateViews(t: Float) {
        val viewsToRotate = listOf(
            binding.btnMenu,
            binding.bottomPanel.findViewById(R.id.btn_flash),
            binding.bottomPanel.findViewById(R.id.btn_switch),
            binding.btnAlbum,
            binding.topBar.findViewById(R.id.btn_resolution),
            binding.topBar.findViewById(R.id.btn_fps),
            binding.topBar.findViewById(R.id.btn_ev),
            binding.topBar.findViewById(R.id.btn_mode),
            binding.topBar.findViewById(R.id.btn_ratio),
            findViewById(R.id.capture_progress_view)
        )
        
        viewsToRotate.forEach { view ->
            view?.let {
                ObjectAnimator.ofFloat(it, View.ROTATION, it.rotation, t).apply {
                    duration = 300
                    start()
                }
            }
        }
        
        // Rotate lens selector buttons
        val lensContainer = binding.lensSelectorContainer
        for (i in 0 until lensContainer.childCount) {
            val child = lensContainer.getChildAt(i)
            ObjectAnimator.ofFloat(child, View.ROTATION, child.rotation, t).apply {
                duration = 300
                start()
            }
        }
    }

    override fun onResume() { 
        super.onResume()
        binding.previewBlurOverlay.visibility = android.view.View.GONE
        binding.previewBlurOverlay.alpha = 0f
        cameraHelper.startBackgroundThread()
        faceTracker.start()
        gestureTracker?.start()
        
        if (binding.previewArea.isAvailable) {
            binding.previewArea.postDelayed({
                cameraHelper.openCamera(binding.previewArea)
            }, 250)
        }
        
        orientationEventListener?.enable()
        
        val settings = com.example.optik.settings.SettingsManager.getInstance(this)
        binding.overlayView.setGridVisible(settings.isGridEnabled)
        
        val levelPitch = findViewById<com.example.optik.view.LevelIndicatorView>(R.id.level_pitch)
        val levelRoll = findViewById<com.example.optik.view.LevelIndicatorView>(R.id.level_roll)
        if (settings.isLevelEnabled) {
            levelPitch?.visibility = android.view.View.VISIBLE
            levelRoll?.visibility = android.view.View.VISIBLE
            levelSensorHelper?.start()
        } else {
            levelPitch?.visibility = android.view.View.GONE
            levelRoll?.visibility = android.view.View.GONE
            levelSensorHelper?.stop()
        }
    }

    override fun onPause() { 
        cameraHelper.closeCamera()
        cameraHelper.stopBackgroundThread()
        orientationEventListener?.disable()
        faceTracker.close()
        gestureTracker?.close()
        levelSensorHelper?.stop()
        super.onPause() 
    }

    override fun onDestroy() {
        super.onDestroy()
        soundHelper.release()
        try { objectTracker.close() } catch (e: Exception) {}
    }
}
