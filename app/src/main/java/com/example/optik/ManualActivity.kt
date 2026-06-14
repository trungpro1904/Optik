package com.example.optik

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import android.view.OrientationEventListener
import android.animation.ObjectAnimator
import android.view.OrientationEventListener.ORIENTATION_UNKNOWN
import com.example.optik.databinding.ActivityManualBinding
import com.example.optik.view.LUTAdapter
import com.example.optik.settings.SettingsManager

import com.example.optik.camera.CameraManagerHelper
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.graphics.RectF

import com.example.optik.settings.ExposureHelper

class ManualActivity : AppCompatActivity() {
    private lateinit var binding: ActivityManualBinding
    private lateinit var cameraHelper: CameraManagerHelper
    private lateinit var faceTracker: com.example.optik.camera.FaceTracker
    private var touchLockedFaceCenter: android.graphics.PointF? = null
    private var isTouchFocusLocked = false
    private var isCapturing = false
    private lateinit var objectTracker: com.example.optik.camera.ObjectTracker
    private var trackedObjectId: Int? = null
    private var lumaCallback: ((luma: Double) -> Unit)? = null
    private var savingCount = 0
    private var isTrackingFace = false
    
    // Dynamic ISO bounds from Camera2 API
    private var currentMinIso: Int = 50
    private var currentMaxIso: Int = 3200
    
    // WB States
    private var currentWbMode = "AWB"
    private var k1Kelvin = 5500
    private var k1TintAB = 0
    private var k1TintGM = 0
    private var k2Kelvin = 5500
    private var k2TintAB = 0
    private var k2TintGM = 0
    private var c1TintAB = 0
    private var c1TintGM = 0
    private var c2TintAB = 0
    private var c2TintGM = 0
    
    private var latestObjects: List<com.google.mlkit.vision.objects.DetectedObject> = emptyList()
    private var maxCameraMp = 12
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private var infoBarUpdateRunnable: Runnable? = null
    private var orientationEventListener: android.view.OrientationEventListener? = null
    private var currentRotation: Float = 0f
    private var levelSensorHelper: com.example.optik.camera.LevelSensorHelper? = null
    private var dispState = 3 // 3 = both Grid and Level ON
    
    private lateinit var soundHelper: com.example.optik.camera.SoundHelper
    
    private var isSettingsPanelOpen = false

    private fun setupOrientationListener() {
        orientationEventListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val target = when (o) {
                    in 45..134 -> 270f
                    in 135..224 -> 180f
                    in 225..314 -> 90f
                    else -> 0f
                }
                if (target != currentRotation) {
                    rotateViews(target)
                    currentRotation = target
                }
            }
        }
    }

    private fun rotateViews(t: Float) {
        val viewsToRotate = listOf(
            binding.btnMenu,
            binding.btnAlbum,
            binding.bottomPanel.findViewById<View>(R.id.tv_mp),
            findViewById(R.id.focal_length),
            findViewById(R.id.btn_mode),
            findViewById(R.id.btn_disp),
            binding.quickSettings.btnMetering,
            binding.quickSettings.btnIsoQuick,
            binding.quickSettings.btnShutterQuick,
            binding.quickSettings.btnRatioQuick,
            binding.quickSettings.btnExtraQuick,
            binding.quickSettings.btnFlashQuick,
            binding.quickSettings.btnFocusQuick,
            binding.quickSettings.btnEvQuick,
            binding.quickSettings.btnFormatQuick,
            binding.quickSettings.btnWbQuick
        )

        viewsToRotate.forEach { view ->
            view?.let {
                ObjectAnimator.ofFloat(it, View.ROTATION, it.rotation, t).apply {
                    duration = 300
                    start()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        SettingsManager.getInstance(this).lastUsedMode = 1 // Manual
        
        binding = ActivityManualBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        soundHelper = com.example.optik.camera.SoundHelper(this)

        cameraHelper = CameraManagerHelper(this)
        
        cameraHelper.onEvCalculated = { ev ->
            val formattedEv = String.format("%.1f", ev)
            val signedEv = if (ev > 0) "+$formattedEv" else formattedEv
            binding.quickSettings.tvEvQuickVal.text = formattedEv
            binding.tvEv.text = signedEv
        }
        
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
        
        faceTracker = com.example.optik.camera.FaceTracker(this) { result ->
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
                                faceTracker.processImage(mpImage, currentRotation.toInt(), android.os.SystemClock.uptimeMillis())
                            } catch (e: Exception) {}
                        }
                    }
                } catch (e: Exception) {}
            }
        }

        loadQuickSettings()
        setupUI()
        setupInfoBar()
        setupOrientationListener()

        updateAspectRatio(SettingsManager.getInstance(this).aspectRatio)

        // Khởi tạo camera ngay lập tức
        cameraHelper.startBackgroundThread()
        setupCamera()
        
        levelSensorHelper = com.example.optik.camera.LevelSensorHelper(this) { pitch, roll ->
            val levelPitch = findViewById<com.example.optik.view.LevelIndicatorView>(R.id.level_pitch)
            val levelRoll = findViewById<com.example.optik.view.LevelIndicatorView>(R.id.level_roll)
            
            levelPitch?.isVertical = true
            levelPitch?.angle = pitch
            
            levelRoll?.isVertical = false
            levelRoll?.angle = roll
        }
    }

    private fun loadQuickSettings() {
        val settings = SettingsManager.getInstance(this)
        
        currentWbMode = settings.manualWbMode
        if (currentWbMode == "K1") {
            k1Kelvin = settings.manualKelvin
            k1TintAB = settings.manualTintAB
            k1TintGM = settings.manualTintGM
        } else if (currentWbMode == "K2") {
            k2Kelvin = settings.manualKelvin
            k2TintAB = settings.manualTintAB
            k2TintGM = settings.manualTintGM
        } else if (currentWbMode == "C1") {
            c1TintAB = settings.manualTintAB
            c1TintGM = settings.manualTintGM
        } else if (currentWbMode == "C2") {
            c2TintAB = settings.manualTintAB
            c2TintGM = settings.manualTintGM
        }
        
        binding.quickSettings.tvWbQuickVal.text = currentWbMode
        
        val meteringIcon = when(settings.meteringMode) { 1 -> R.drawable.center; 2 -> R.drawable.spot; else -> R.drawable.area_meter }
        val iconViewQuick = binding.quickSettings.btnMetering.getChildAt(0) as? android.widget.ImageView
        iconViewQuick?.setImageResource(meteringIcon)
        
        if (!settings.isIsoAuto) {
            val isoStr = com.example.optik.settings.ExposureHelper.formatIso(settings.manualIsoValue)
            binding.quickSettings.tvIsoQuickVal.text = isoStr
            binding.tvIso.text = "ISO $isoStr"
        }
        
        if (!settings.isShutterAuto) {
            val sStr = com.example.optik.settings.ExposureHelper.formatShutterSpeed(settings.manualShutterValue)
            binding.quickSettings.tvShutterQuickVal.text = sStr
            binding.tvShutter.text = sStr
        }
        
        if (settings.focusMode == 1) {
            binding.quickSettings.tvFocusQuickVal.text = "MF"
        } else {
            binding.quickSettings.tvFocusQuickVal.text = "AF"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        soundHelper.release()
    }

    private fun updateDisp() {
        val settings = com.example.optik.settings.SettingsManager.getInstance(this)
        val showGrid = (dispState and 1) != 0 && settings.isGridEnabled
        val showLevel = (dispState and 2) != 0 && settings.isLevelEnabled
        
        binding.overlayView.setGridVisible(showGrid)
        
        val levelPitch = findViewById<com.example.optik.view.LevelIndicatorView>(R.id.level_pitch)
        val levelRoll = findViewById<com.example.optik.view.LevelIndicatorView>(R.id.level_roll)
        
        if (showLevel) {
            levelPitch?.visibility = View.VISIBLE
            levelRoll?.visibility = View.VISIBLE
            levelSensorHelper?.start()
        } else {
            levelPitch?.visibility = View.GONE
            levelRoll?.visibility = View.GONE
            levelSensorHelper?.stop()
        }
    }

    private fun setupCamera() {
        binding.previewArea.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                cameraHelper.openCamera(binding.previewArea)
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
        }

        binding.previewArea.setOnTouchListener { _, event ->
            if (isCapturing) return@setOnTouchListener true
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
        
        cameraHelper.onThumbnailAvailable = { bitmap ->
            runOnUiThread {
                findViewById<android.widget.ImageView>(R.id.album_thumbnail)?.setImageBitmap(bitmap)
            }
        }

        cameraHelper.onImageSaving = {
            runOnUiThread {
                savingCount++
                binding.albumProgress.visibility = View.VISIBLE
            }
        }
        
        cameraHelper.onPictureSaved = { success ->
            runOnUiThread {
                savingCount--
                if (savingCount <= 0) {
                    savingCount = 0
                    isCapturing = false
                    binding.albumProgress.visibility = View.GONE
                }
                if (success) {
                    updateAlbumThumbnail()
                } else {
                    android.widget.Toast.makeText(this@ManualActivity, "Failed to save image", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
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
    }

    private fun updateAspectRatio(r: String) {
        val params = binding.previewContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        when (r) { 
            "4:3" -> params.dimensionRatio = "3:4"
            "16:9" -> params.dimensionRatio = "9:16"
            "1:1" -> params.dimensionRatio = "1:1"
            "Full" -> params.dimensionRatio = null
            else -> params.dimensionRatio = "3:4" 
        }
        binding.previewContainer.layoutParams = params
    }

    private fun updateAlbumThumbnail() {
        val collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(android.provider.MediaStore.Images.Media._ID)
        val selection = "${android.provider.MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%Pictures/Optik%")
        val sortOrder = "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC"
        
        contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID)
                val id = cursor.getLong(idColumn)
                val uri = android.content.ContentUris.withAppendedId(collection, id)
                try {
                    val bitmap = contentResolver.loadThumbnail(uri, android.util.Size(128, 128), null)
                    runOnUiThread {
                        findViewById<android.widget.ImageView>(R.id.album_thumbnail)?.setImageBitmap(bitmap)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ManualActivity", "Error loading thumbnail for uri: $uri", e)
                }
            }
        }
    }

    /**
     * Cài đặt info bar: 
     * - Lấy thông tin khẩu độ, ISO range, focal length từ Camera2 API
     * - Cập nhật liên tục shutter speed và ISO từ AE engine (25fps)
     * - EV hiển thị 0.0 (sẽ tính toán sau khi Manual mode hoạt động)
     */
    private fun setupInfoBar() {
        // Nhận thông tin cảm biến khi camera mở
        cameraHelper.onCameraInfoAvailable = { info ->
            runOnUiThread {
                // Hiển thị khẩu độ (cố định theo ống kính)
                if (info.aperture > 0f) {
                    binding.tvAperture.text = String.format("F %.1f", info.aperture)
                } else {
                    binding.tvAperture.text = "F --"
                }

                // Hiển thị tiêu cự quy đổi 35mm ở top bar
                if (info.focalLength35mm > 0f) {
                    binding.focalLength.text = String.format("%.0f\nmm", info.focalLength35mm)
                } else if (info.focalLength > 0f) {
                    binding.focalLength.text = String.format("%.0f\nmm", info.focalLength)
                }
                
                // Cập nhật ISO range từ API
                if (info.minIso > 0) currentMinIso = info.minIso
                if (info.maxIso > 0) currentMaxIso = info.maxIso
            }
        }

        cameraHelper.onCamerasAvailable = { lenses ->
            runOnUiThread { setupLensSelector(lenses) }
        }

        cameraHelper.onResolutionsAvailable = { sizes -> 
            runOnUiThread { 
                maxCameraMp = sizes.maxOfOrNull { it.width * it.height / 1_000_000 } ?: 12
                val btnRes = binding.tvMp
                btnRes.text = SettingsManager.getInstance(this@ManualActivity).photoResolution.ifEmpty { "12mp" }
            } 
        }

        // Bắt đầu polling info bar (25fps = 40ms interval)
        startInfoBarUpdates()
    }

    private fun startInfoBarUpdates() {
        infoBarUpdateRunnable = object : Runnable {
            override fun run() {
                updateInfoBarValues()
                mainHandler.postDelayed(this, 40) // 25fps
            }
        }
        infoBarUpdateRunnable?.let { mainHandler.post(it) }
    }

    private fun stopInfoBarUpdates() {
        infoBarUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
        infoBarUpdateRunnable = null
    }

    private fun updateInfoBarValues() {
        val settings = SettingsManager.getInstance(this)
        
        // Lấy giá trị hiện tại từ AE engine (preview)
        val shutterNs = cameraHelper.getCurrentShutterNs()
        val iso = cameraHelper.getCurrentIso()

        val shutterStr = ExposureHelper.formatShutterSpeed(shutterNs)
        val isoStr = ExposureHelper.formatIso(iso)

        binding.tvShutter.text = shutterStr
        binding.quickSettings.tvShutterQuickVal.text = if (settings.isShutterAuto) "AUTO\n$shutterStr" else shutterStr

        binding.tvIso.text = "ISO $isoStr"
        binding.quickSettings.tvIsoQuickVal.text = if (settings.isIsoAuto) "AUTO\n$isoStr" else isoStr

        // Cập nhật EV
        val isManualExposure = !settings.isIsoAuto && !settings.isShutterAuto
        if (isManualExposure) {
            val ev = cameraHelper.currentCalculatedEv
            val formattedEv = String.format("%.1f", ev).replace(",", ".")
            val signedEv = if (ev > 0) "+$formattedEv" else formattedEv
            binding.tvEv.text = signedEv
            binding.quickSettings.tvEvQuickVal.text = formattedEv
            
            binding.quickSettings.btnEvQuick.isEnabled = false
            binding.quickSettings.btnEvQuick.alpha = 0.5f
        } else {
            val evComp = cameraHelper.currentEvCompensation
            val formattedEvComp = String.format(java.util.Locale.US, "%.1f", evComp)
            val signedEvComp = String.format(java.util.Locale.US, "%+.1f", evComp)
            binding.tvEv.text = "A $signedEvComp"
            binding.quickSettings.tvEvQuickVal.text = formattedEvComp
            
            binding.quickSettings.btnEvQuick.isEnabled = true
            binding.quickSettings.btnEvQuick.alpha = 1.0f
        }
    }

    private var currentSelectedManualLensBtn: android.widget.TextView? = null

    private fun setupLensSelector(lenses: List<com.example.optik.camera.CameraLens>) {
        val container = binding.lensContainer
        container.removeAllViews()
        if (lenses.isEmpty()) return
        
        val density = resources.displayMetrics.density
        val size = (56 * density).toInt()
        val margin = (12 * density).toInt()
        
        for (lens in lenses) {
            val tv = android.widget.TextView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply {
                    setMargins(margin, margin, margin, margin)
                }
                gravity = android.view.Gravity.CENTER
                text = "${lens.focalLength35mm}\nmm"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 14f
                background = getDrawable(R.drawable.circle_border_white)
                
                setOnClickListener {
                    if (currentSelectedManualLensBtn != this) {
                        currentSelectedManualLensBtn?.background = getDrawable(R.drawable.circle_border_white)
                        currentSelectedManualLensBtn?.setTextColor(android.graphics.Color.WHITE)
                        
                        background = getDrawable(R.drawable.circle_white)
                        setTextColor(android.graphics.Color.BLACK)
                        
                        currentSelectedManualLensBtn = this
                        cameraHelper.switchLens(binding.previewArea, lens.id)
                        
                        binding.lensSelectionPanel.visibility = View.GONE
                    }
                }
            }
            container.addView(tv)
            if (lens.id == cameraHelper.currentCameraId || (currentSelectedManualLensBtn == null && lens.zoomRatio == 1.0f)) {
                currentSelectedManualLensBtn = tv
                tv.background = getDrawable(R.drawable.circle_white)
                tv.setTextColor(android.graphics.Color.BLACK)
                binding.focalLength.text = "${lens.focalLength35mm}\nmm"
            }
        }
    }

    private fun setupUI() {
        updateRatioPanelUI()

        findViewById<android.view.View>(R.id.btn_disp).setOnClickListener {
            dispState = (dispState + 1) % 4
            updateDisp()
        }

        binding.btnMenu.setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
        
        binding.focalLength.setOnClickListener {
            binding.lensSelectionPanel.visibility = View.VISIBLE
        }
        
        binding.btnCloseLens.setOnClickListener {
            binding.lensSelectionPanel.visibility = View.GONE
        }

        binding.btnAlbum.setOnClickListener {
            if (isCapturing) return@setOnClickListener
            if (binding.albumProgress.visibility == View.VISIBLE) {
                android.widget.Toast.makeText(this, "Đang xử lý ảnh...", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(android.provider.MediaStore.Images.Media._ID)
            val selection = "${android.provider.MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("%Pictures/Optik%")
            val sortOrder = "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC"
            
            var latestUri: android.net.Uri? = null
            contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID)
                    val id = cursor.getLong(idColumn)
                    latestUri = android.content.ContentUris.withAppendedId(collection, id)
                }
            }
            
            if (latestUri != null) {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.setDataAndType(latestUri, "image/*")
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(intent)
            } else {
                val intent = Intent(Intent.ACTION_PICK)
                intent.type = "image/*"
                startActivity(intent)
            }
        }

        // Mode switch wheel - nhấn vào chữ "M" để chuyển chế độ
        val btnMode = findViewById<android.widget.TextView>(R.id.btn_mode)
        btnMode?.setOnClickListener {
            android.util.Log.d("ManualActivity", "btnMode clicked - showing overlay")
            val overlayBinding = binding.modeWheelOverlay
            overlayBinding.root.visibility = android.view.View.VISIBLE
            overlayBinding.modeWheel.setInitialMode(0) // Đang ở Manual (index 0)
            
            overlayBinding.root.setOnClickListener { 
                overlayBinding.root.visibility = android.view.View.GONE 
            }
            
            overlayBinding.modeWheel.onModeSelected = { mode ->
                overlayBinding.root.visibility = android.view.View.GONE
                if (mode == 1) { // Chọn Basic
                    SettingsManager.getInstance(this).lastUsedMode = 0
                    stopInfoBarUpdates()
                    cameraHelper.closeCamera()
                    cameraHelper.stopBackgroundThread()
                    
                    startActivity(Intent(this, BasicActivity::class.java))
                    finish()
                }
            }
        }

        binding.btnExpand.setOnClickListener {
            android.util.Log.d("ManualActivity", "btnExpand clicked")
            togglePanel()
        }

        binding.quickSettings.btnEvQuick.setOnClickListener {
            // Placeholder: maybe open a slider later?
        }
        
        binding.quickSettings.btnFormatQuick.setOnClickListener {
            showSettingsPanel("Định dạng tệp", 3)
        }
        
        binding.quickSettings.btnFlashQuick.setOnClickListener {
            showSettingsPanel("Chế độ đèn Flash", 4)
        }
        
        binding.quickSettings.btnExtraQuick.setOnClickListener {
            showSettingsPanel("Chế độ chụp khác", 5)
        }
        
        binding.quickSettings.btnMetering.setOnClickListener {
            showSettingsPanel("Chế độ đo sáng", 0)
        }

        binding.quickSettings.btnFocusQuick.setOnClickListener {
            showSettingsPanel("Chế độ lấy nét", 6)
        }

        binding.quickSettings.btnIsoQuick.setOnClickListener {
            showSettingsPanel("ISO", 1)
        }

        binding.quickSettings.btnShutterQuick.setOnClickListener {
            showSettingsPanel("Tốc độ màn trập", 2)
        }
        
        binding.quickSettings.btnWbQuick.setOnClickListener {
            showSettingsPanel("Cân bằng trắng", 7)
        }

        binding.quickSettings.btnRatioQuick.setOnClickListener {
            binding.quickSettings.root.visibility = View.INVISIBLE
            binding.ratioSelectionPanel.visibility = View.VISIBLE
            updateRatioPanelUI()
        }

        binding.quickSettings.btnEvQuick.setOnClickListener {
            val settings = com.example.optik.settings.SettingsManager.getInstance(this)
            if (!settings.isIsoAuto && !settings.isShutterAuto) {
                android.widget.Toast.makeText(this, "EV chỉ khả dụng khi ISO hoặc Shutter ở AUTO", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.quickSettings.root.visibility = View.INVISIBLE
            binding.evAdjustmentPanel.visibility = View.VISIBLE
            binding.evSlider.setEvValue(cameraHelper.currentEvCompensation)
        }

        binding.evSlider.setOnCloseListener {
            binding.evAdjustmentPanel.visibility = View.GONE
            binding.quickSettings.root.visibility = View.VISIBLE
        }

        binding.evSlider.setOnEvChangeListener { ev ->
            cameraHelper.setExposureCompensation(ev)
            val formattedEv = String.format(java.util.Locale.US, "%.1f", ev)
            val signedEv = String.format(java.util.Locale.US, "%+.1f", ev)
            binding.quickSettings.tvEvQuickVal.text = formattedEv
            binding.tvEv.text = "A $signedEv"
        }

        binding.btnCloseRatioPanel.setOnClickListener {
            binding.ratioSelectionPanel.visibility = View.GONE
            binding.quickSettings.root.visibility = View.VISIBLE
        }

        val ratioClickListener = View.OnClickListener { v ->
            val ratio = (v as android.widget.TextView).text.toString()
            SettingsManager.getInstance(this).aspectRatio = ratio
            binding.quickSettings.tvRatioQuickVal.text = ratio
            updateRatioPanelUI()
            updateAspectRatio(ratio)
            
            cameraHelper.closeCamera()
            cameraHelper.openCamera(binding.previewArea)
        }

        binding.tvRatio43.setOnClickListener(ratioClickListener)
        binding.tvRatio169.setOnClickListener(ratioClickListener)
        binding.tvRatio11.setOnClickListener(ratioClickListener)

        binding.tvMp.setOnClickListener { showResolutionPopup() }

        binding.switchLock.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (binding.panelContainer.visibility == View.VISIBLE) togglePanel()
                if (binding.lensSelectionPanel.visibility == View.VISIBLE) binding.lensSelectionPanel.visibility = View.GONE
            }
            
            val alpha = if (isChecked) 0.3f else 1.0f
            val isEnabled = !isChecked
            
            val viewsToFade = listOf(
                binding.topBar, binding.infoBar, binding.btnExpand,
                binding.btnAlbum, binding.btnShutter, binding.tvMp,
                findViewById<View>(R.id.btn_disp)
            )
            for (v in viewsToFade) {
                v?.alpha = alpha
            }
            
            fun setEnabledRecursive(view: View?, enabled: Boolean) {
                if (view == null) return
                view.isEnabled = enabled
                view.isClickable = enabled
                if (view is android.view.ViewGroup) {
                    for (i in 0 until view.childCount) {
                        setEnabledRecursive(view.getChildAt(i), enabled)
                    }
                }
            }
            
            setEnabledRecursive(binding.topBar, isEnabled)
            setEnabledRecursive(binding.infoBar, isEnabled)
            setEnabledRecursive(binding.panelContainer, isEnabled)
            binding.btnExpand.isEnabled = isEnabled
            binding.btnAlbum.isEnabled = isEnabled
            binding.btnShutter.isEnabled = isEnabled
            binding.tvMp.isEnabled = isEnabled
            findViewById<View>(R.id.btn_disp)?.isEnabled = isEnabled
        }

        var continuousCaptureRunnable: Runnable? = null
        var isHolding = false

        binding.btnShutter.setOnTouchListener { v, event ->
            val settings = SettingsManager.getInstance(this)
            
            if (settings.driveMode != 1) { // Nếu không phải chế độ Burst
                if (event.action == android.view.MotionEvent.ACTION_UP) {
                    v.performClick()
                }
                return@setOnTouchListener false
            }

            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    isHolding = true
                    v.isPressed = true
                    playShutterAnimation()
                    
                    continuousCaptureRunnable = object : Runnable {
                        override fun run() {
                            if (isHolding) {
                                soundHelper.playShutter()
                                playBurstShutterAnimation()
                                cameraHelper.captureImage()
                                val delay = if (SettingsManager.getInstance(this@ManualActivity).photoFormat.contains("RAW")) 200L else 100L
                                mainHandler.postDelayed(this, delay)
                            }
                        }
                    }
                    mainHandler.post(continuousCaptureRunnable!!)
                    return@setOnTouchListener true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    isHolding = false
                    v.isPressed = false
                    continuousCaptureRunnable?.let { mainHandler.removeCallbacks(it) }
                    continuousCaptureRunnable = null
                    return@setOnTouchListener true
                }
            }
            false
        }

        binding.btnShutter.setOnClickListener {
            val settings = SettingsManager.getInstance(this)
            if (settings.driveMode == 1) return@setOnClickListener // Đã xử lý ở onTouch
            
            if (isCapturing) return@setOnClickListener
            
            when (settings.driveMode) {
                2 -> startCountdownTimer(10)
                3 -> startCountdownTimer(3)
                0 -> { // Đơn (Single)
                    performCapture()
                }
            }
        }
    }

    private fun playShutterAnimation() {
        val overlay = binding.previewBlurOverlay
        overlay.visibility = android.view.View.VISIBLE
        overlay.setBackgroundColor(android.graphics.Color.BLACK)
        overlay.alpha = 1f
        overlay.animate().alpha(0f).setDuration(300).withEndAction { 
            overlay.visibility = android.view.View.GONE
            overlay.setBackgroundColor(android.graphics.Color.parseColor("#A0000000")) 
        }.start()
    }

    private fun playBurstShutterAnimation() {
        val border = findViewById<android.view.View>(R.id.burst_flash_border) ?: return
        border.visibility = android.view.View.VISIBLE
        border.alpha = 1f
        border.animate().alpha(0f).setDuration(80).withEndAction { 
            border.visibility = android.view.View.GONE
        }.start()
    }

    private var countDownTimer: android.os.CountDownTimer? = null

    private fun startCountdownTimer(seconds: Int) {
        if (countDownTimer != null) {
            countDownTimer?.cancel()
            countDownTimer = null
            binding.tvCountdown.visibility = View.GONE
            isCapturing = false
            return
        }
        
        isCapturing = true
        binding.tvCountdown.visibility = View.VISIBLE
        
        countDownTimer = object : android.os.CountDownTimer((seconds * 1000).toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = Math.ceil(millisUntilFinished / 1000.0).toInt()
                binding.tvCountdown.text = sec.toString()
                
                // Play beep sound (urgent if <= 3s)
                if (sec <= 3) {
                    soundHelper.playBeep(2f)
                } else {
                    soundHelper.playBeep(1f)
                }
                
                binding.tvCountdown.scaleX = 1.2f
                binding.tvCountdown.scaleY = 1.2f
                binding.tvCountdown.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }
            
            override fun onFinish() {
                countDownTimer = null
                binding.tvCountdown.visibility = View.GONE
                performCapture()
            }
        }.start()
    }

    private fun showResolutionPopup() {
        val tvMp = binding.tvMp ?: return
        val popupView = layoutInflater.inflate(R.layout.popup_resolution, null)
        val popup = android.widget.PopupWindow(popupView, -2, -2, true)
        
        val clickListener = View.OnClickListener { v -> 
            if (v is android.widget.TextView) { 
                tvMp.text = v.text
                SettingsManager.getInstance(this).photoResolution = v.text.toString()
                popup.dismiss() 
            } 
        }
        val v48 = popupView.findViewById<android.widget.TextView>(R.id.res_48mp)
        val v24 = popupView.findViewById<android.widget.TextView>(R.id.res_24mp)
        val v12 = popupView.findViewById<android.widget.TextView>(R.id.res_12mp)
        
        v48?.visibility = if (maxCameraMp >= 48) View.VISIBLE else View.GONE
        v24?.visibility = if (maxCameraMp >= 24) View.VISIBLE else View.GONE
        v12?.visibility = View.VISIBLE
        
        listOfNotNull(v48, v24, v12).forEach { it.setOnClickListener(clickListener) }
        popup.showAsDropDown(tvMp)
    }

    private fun updateRatioPanelUI() {
        val selected = SettingsManager.getInstance(this).aspectRatio
        val white = android.graphics.Color.WHITE
        val orange = android.graphics.Color.parseColor("#FF9800")
        
        binding.tvRatio43.setTextColor(if (selected == "4:3") orange else white)
        binding.tvRatio169.setTextColor(if (selected == "16:9") orange else white)
        binding.tvRatio11.setTextColor(if (selected == "1:1") orange else white)
    }

    private fun togglePanel() {
        val container = binding.panelContainer
        val isVisible = container.visibility == View.VISIBLE
        
        if (isVisible) {
            // Reset state
            binding.ratioSelectionPanel.visibility = View.GONE
            binding.evAdjustmentPanel.visibility = View.GONE
            binding.quickSettings.root.visibility = View.VISIBLE
            
            // Hide animation
            container.animate()
                .translationY(300f)
                .alpha(0f)
                .setDuration(250)
                .withEndAction { 
                    container.visibility = View.GONE
                }
                .start()
            binding.btnExpand.animate().rotation(180f).setDuration(250).start()
        } else {
            // Show animation
            val settings = SettingsManager.getInstance(this)
            binding.quickSettings.tvRatioQuickVal.text = settings.aspectRatio
            binding.quickSettings.tvEvQuickVal.text = String.format(java.util.Locale.US, "%.1f", cameraHelper.currentEvCompensation)
            binding.quickSettings.tvFormatQuickVal.text = settings.photoFormat
            
            val flashIcons = listOf(R.drawable.ic_flash_off, R.drawable.ic_flash_on, R.drawable.flashlight_on)
            binding.quickSettings.ivFlashQuickIcon.setImageResource(flashIcons[settings.flashMode.coerceIn(0, 2)])
            
            val driveIcons = try {
                listOf(R.drawable.square, R.drawable.burst, R.drawable._10s_count, R.drawable._3s_count)
            } catch (e: Exception) {
                listOf(R.drawable.square, R.drawable.square, R.drawable.square, R.drawable.square)
            }
            binding.quickSettings.btnExtraQuick.getChildAt(0).setBackgroundResource(driveIcons[settings.driveMode.coerceIn(0, 3)])

            container.visibility = View.VISIBLE
            container.alpha = 0f
            container.translationY = 300f
            
            container.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(300)
                .start()
            binding.btnExpand.animate().rotation(0f).setDuration(300).start()
        }
    }

    private fun performCapture() {
        isCapturing = true
        soundHelper.playShutter()
        playShutterAnimation()
        
        val currentShutterMs = cameraHelper.getCurrentShutterNs() / 1_000_000L
        if (currentShutterMs >= 125) {
            val progressView = findViewById<com.example.optik.view.CaptureProgressView>(R.id.capture_progress_view)
            progressView?.startProgress(currentShutterMs)
        }
        
        cameraHelper.captureImage()
    }
    
    private fun showSettingsPanel(title: String, type: Int) {
        isSettingsPanelOpen = true
        binding.infoBar.visibility = View.INVISIBLE
        binding.panelContainer.visibility = View.INVISIBLE
        binding.ratioSelectionPanel.visibility = View.GONE
        binding.evAdjustmentPanel.visibility = View.GONE
        binding.manualSettingsPanelInclude.wbModeContainer.visibility = View.GONE
        
        val panel = binding.manualSettingsPanelInclude.root
        panel.visibility = View.VISIBLE
        binding.manualSettingsPanelInclude.tvSettingsTitle.text = title
        
        val containerOptions = binding.manualSettingsPanelInclude.containerOptions
        val pickerContainer = binding.manualSettingsPanelInclude.pickerContainer
        val rvPicker = binding.manualSettingsPanelInclude.rvPicker
        
        containerOptions.removeAllViews()
        
        when (type) {
            0 -> { // Metering
                containerOptions.visibility = View.VISIBLE
                pickerContainer.visibility = View.GONE
                populateMeteringOptions(containerOptions)
            }
            1 -> { // ISO
                containerOptions.visibility = View.GONE
                pickerContainer.visibility = View.VISIBLE
                populateIsoOptions(rvPicker)
            }
            2 -> { // Shutter
                containerOptions.visibility = View.GONE
                pickerContainer.visibility = View.VISIBLE
                populateShutterOptions(rvPicker)
            }
            3 -> { // Format
                containerOptions.visibility = View.VISIBLE
                pickerContainer.visibility = View.GONE
                populateFormatOptions(containerOptions)
            }
            4 -> { // Flash
                containerOptions.visibility = View.VISIBLE
                pickerContainer.visibility = View.GONE
                populateFlashOptions(containerOptions)
            }
            5 -> { // Drive Mode
                containerOptions.visibility = android.view.View.VISIBLE
                pickerContainer.visibility = android.view.View.GONE
                binding.manualSettingsPanelInclude.focusSliderContainer.visibility = android.view.View.GONE
                populateDriveModeOptions(containerOptions)
            }
            6 -> { // Focus
                populateFocusOptions()
            }
            7 -> { // WB
                containerOptions.visibility = View.GONE
                binding.manualSettingsPanelInclude.focusSliderContainer.visibility = View.GONE
                binding.manualSettingsPanelInclude.wbModeContainer.visibility = View.VISIBLE
                setupWbPanel(pickerContainer, rvPicker)
            }
        }
        
        binding.manualSettingsPanelInclude.btnCloseSettings.setOnClickListener {
            closeSettingsPanel()
        }
    }

    private fun closeSettingsPanel() {
        isSettingsPanelOpen = false
        binding.manualSettingsPanelInclude.root.visibility = android.view.View.GONE
        binding.wbGridContainer.visibility = View.GONE
        binding.infoBar.visibility = android.view.View.VISIBLE
        binding.panelContainer.visibility = android.view.View.VISIBLE
        binding.manualSettingsPanelInclude.focusSliderContainer.visibility = android.view.View.GONE
    }

    private fun populateFocusOptions() {
        val containerOptions = binding.manualSettingsPanelInclude.containerOptions
        val pickerContainer = binding.manualSettingsPanelInclude.pickerContainer
        val focusSliderContainer = binding.manualSettingsPanelInclude.focusSliderContainer ?: return
        
        pickerContainer.visibility = android.view.View.GONE
        containerOptions.visibility = android.view.View.VISIBLE
        focusSliderContainer.visibility = android.view.View.GONE
        containerOptions.removeAllViews()
        
        val settings = SettingsManager.getInstance(this)
        val texts = listOf("AF", "MF")
        val modes = listOf(0, 1)
        
        for (i in texts.indices) {
            val item = layoutInflater.inflate(R.layout.item_manual_option, containerOptions, false)
            val iconView = item.findViewById<android.widget.ImageView>(R.id.item_icon)
            val textView = item.findViewById<android.widget.TextView>(R.id.item_text)
            
            iconView.visibility = android.view.View.GONE
            textView.text = texts[i]
            
            if (settings.focusMode == modes[i]) {
                textView.setTextColor(android.graphics.Color.parseColor("#FF9800"))
            } else {
                textView.setTextColor(android.graphics.Color.WHITE)
            }
            
            item.setOnClickListener {
                settings.focusMode = modes[i]
                if (modes[i] == 1) { // MF
                    showFocusSlider()
                } else { // AF
                    cameraHelper.updateFocusMode(0, 0f)
                    val focusText = binding.quickSettings.tvFocusQuickVal
                    focusText.text = "AF"
                    closeSettingsPanel()
                }
            }
            containerOptions.addView(item)
        }
    }

    
    
    private fun setupWbPanel(pickerContainer: View, rvPicker: androidx.recyclerview.widget.RecyclerView) {
        val wbModes = listOf("AWB", "K1", "K2", "C1", "C2")
        val containers = listOf(
            binding.manualSettingsPanelInclude.wbModeAwb,
            binding.manualSettingsPanelInclude.wbModeK1,
            binding.manualSettingsPanelInclude.wbModeK2,
            binding.manualSettingsPanelInclude.wbModeC1,
            binding.manualSettingsPanelInclude.wbModeC2
        )
        val titles = listOf(
            binding.manualSettingsPanelInclude.tvWbAwbTitle,
            binding.manualSettingsPanelInclude.tvWbK1Title,
            binding.manualSettingsPanelInclude.tvWbK2Title,
            binding.manualSettingsPanelInclude.tvWbC1Title,
            binding.manualSettingsPanelInclude.tvWbC2Title
        )
        
        fun updateWbSelection() {
            for (i in wbModes.indices) {
                val isSelected = (wbModes[i] == currentWbMode)
                titles[i]?.setTextColor(if (isSelected) android.graphics.Color.parseColor("#FF9800") else android.graphics.Color.WHITE)
            }
            binding.quickSettings.tvWbQuickVal.text = currentWbMode
            
            // Toggle Grid
            if (currentWbMode == "AWB") {
                binding.wbGridContainer.visibility = View.GONE
                pickerContainer.visibility = View.GONE
                cameraHelper.updateManualWb("AWB", 0, 0, 0)
            } else {
                binding.wbGridContainer.visibility = View.VISIBLE
                // Toggle Slider
                if (currentWbMode == "K1" || currentWbMode == "K2") {
                    pickerContainer.visibility = View.VISIBLE
                    populateWbKelvinOptions(rvPicker)
                } else {
                    pickerContainer.visibility = View.GONE
                }
                
                // Set Grid Values
                val ab = when(currentWbMode) { "K1" -> k1TintAB; "K2" -> k2TintAB; "C1" -> c1TintAB; else -> c2TintAB }
                val gm = when(currentWbMode) { "K1" -> k1TintGM; "K2" -> k2TintGM; "C1" -> c1TintGM; else -> c2TintGM }
                binding.wbGrid.setTint(ab, gm)
                binding.tvWbTintInfo.text = "AB: $ab   GM: $gm"
                
                applyWbToCamera()
            }
        }
        
        for (i in wbModes.indices) {
            containers[i]?.setOnClickListener {
                currentWbMode = wbModes[i]
                updateWbSelection()
            }
        }
        
        // Listen to Grid changes
        binding.wbGrid.onTintChangedListener = { ab, gm ->
            binding.tvWbTintInfo.text = "AB: $ab   GM: $gm"
            when(currentWbMode) {
                "K1" -> { k1TintAB = ab; k1TintGM = gm }
                "K2" -> { k2TintAB = ab; k2TintGM = gm }
                "C1" -> { c1TintAB = ab; c1TintGM = gm }
                "C2" -> { c2TintAB = ab; c2TintGM = gm }
            }
            applyWbToCamera()
        }
        
        updateWbSelection()
    }
    
    private fun applyWbToCamera() {
        val kelvin = when(currentWbMode) { "K1" -> k1Kelvin; "K2" -> k2Kelvin; "C1" -> 5500; "C2" -> 5500; else -> 0 }
        val ab = when(currentWbMode) { "K1" -> k1TintAB; "K2" -> k2TintAB; "C1" -> c1TintAB; else -> c2TintAB }
        val gm = when(currentWbMode) { "K1" -> k1TintGM; "K2" -> k2TintGM; "C1" -> c1TintGM; else -> c2TintGM }
        cameraHelper.updateManualWb(currentWbMode, kelvin, ab, gm)
    }

    private fun populateWbKelvinOptions(rvPicker: androidx.recyclerview.widget.RecyclerView) {
        val stdList = com.example.optik.settings.WhitebalanceHelper.getStandardKelvinRange()
        val strList = stdList.map { "${it}K" }
        
        val currentK = if (currentWbMode == "K1") k1Kelvin else k2Kelvin
        var initIdx = stdList.indexOf(currentK)
        if (initIdx < 0) initIdx = stdList.indexOf(5500)
        
        setupCarouselPicker(rvPicker, strList, initIdx) { idx ->
            val selK = stdList[idx]
            if (currentWbMode == "K1") k1Kelvin = selK else if (currentWbMode == "K2") k2Kelvin = selK
            applyWbToCamera()
        }
    }

    private fun showFocusSlider() {
        val containerOptions = binding.manualSettingsPanelInclude.containerOptions
        val focusSliderContainer = binding.manualSettingsPanelInclude.focusSliderContainer ?: return
        val seekBar = focusSliderContainer.findViewById<android.widget.SeekBar>(R.id.seek_focus)
        
        containerOptions.visibility = android.view.View.GONE
        focusSliderContainer.visibility = android.view.View.VISIBLE
        
        val settings = SettingsManager.getInstance(this)
        
        // Reverse logic: 0 progress (left) = Macro (10f), 1000 progress (right) = Infinity (0f)
        seekBar.progress = ((10f - settings.manualFocusDistance) * 100f).toInt().coerceIn(0, 1000)
        
        seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val distance = (1000 - progress) / 100f
                    settings.manualFocusDistance = distance
                    cameraHelper.updateFocusMode(1, distance)
                    binding.quickSettings.tvFocusQuickVal.text = "MF"
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        
        // Initial set
        cameraHelper.updateFocusMode(1, settings.manualFocusDistance)
        binding.quickSettings.tvFocusQuickVal.text = "MF"
    }

    private fun populateMeteringOptions(container: android.widget.LinearLayout) {
        val icons = try {
            listOf(R.drawable.area_meter, R.drawable.center, R.drawable.spot) 
        } catch (e: Exception) {
            listOf(R.drawable.ic_close, R.drawable.ic_close, R.drawable.ic_close)
        }
        val texts = listOf("Đa điểm", "Trung tâm", "Điểm")
        val modes = listOf(0, 1, 2)
        
        for (i in icons.indices) {
            val item = layoutInflater.inflate(R.layout.item_manual_option, container, false)
            val iconView = item.findViewById<android.widget.ImageView>(R.id.item_icon)
            val textView = item.findViewById<android.widget.TextView>(R.id.item_text)
            
            iconView.setImageResource(icons[i])
            textView.text = texts[i]
            if (cameraHelper.meteringMode == modes[i]) {
                textView.setTextColor(android.graphics.Color.parseColor("#FF9800"))
                iconView.setColorFilter(android.graphics.Color.parseColor("#FF9800"))
            } else {
                textView.setTextColor(android.graphics.Color.WHITE)
                iconView.setColorFilter(android.graphics.Color.WHITE)
            }
            
            item.setOnClickListener {
                cameraHelper.updateMeteringMode(modes[i])
                SettingsManager.getInstance(this@ManualActivity).meteringMode = modes[i]
                val iconViewQuick = binding.quickSettings.btnMetering.getChildAt(0) as? android.widget.ImageView
                iconViewQuick?.setImageResource(icons[i])
                closeSettingsPanel()
            }
            container.addView(item)
        }
    }

    private var pickerSnapHelper: androidx.recyclerview.widget.LinearSnapHelper? = null

    private fun setupCarouselPicker(
        rv: androidx.recyclerview.widget.RecyclerView,
        items: List<String>,
        selectedIndex: Int,
        onItemSelected: (Int) -> Unit
    ) {
        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
        if (rv.onFlingListener == null) {
            if (pickerSnapHelper == null) {
                pickerSnapHelper = androidx.recyclerview.widget.LinearSnapHelper()
            }
            try {
                pickerSnapHelper?.attachToRecyclerView(rv)
            } catch (e: IllegalStateException) {}
        }
        
        val setupLogic = {
            val rvWidth = rv.width
            val itemWidth = if (rvWidth > 0) rvWidth / 3 else 300 // fallback
            rv.setPadding(itemWidth, 0, itemWidth, 0)
            
            rv.adapter = PickerAdapter(items, itemWidth) { pos ->
                val smoothScroller = object : androidx.recyclerview.widget.LinearSmoothScroller(rv.context) {
                    override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int): Int {
                        val viewCenter = viewStart + (viewEnd - viewStart) / 2
                        val boxCenter = boxStart + (boxEnd - boxStart) / 2
                        return boxCenter - viewCenter
                    }
                }
                smoothScroller.targetPosition = pos
                rv.layoutManager?.startSmoothScroll(smoothScroller)
            }
            
            val validIndex = if (selectedIndex in items.indices) selectedIndex else 0
            val layoutManager = rv.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
            layoutManager.scrollToPositionWithOffset(validIndex, (rvWidth - itemWidth) / 2)
            
            // Setup arrows
            binding.manualSettingsPanelInclude.btnPickerLeft.setOnClickListener {
                val lm = rv.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
                val view = pickerSnapHelper?.findSnapView(lm)
                if (view != null) {
                    val pos = lm.getPosition(view)
                    if (pos > 0) rv.smoothScrollToPosition(pos - 1)
                }
            }
            
            binding.manualSettingsPanelInclude.btnPickerRight.setOnClickListener {
                val lm = rv.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
                val view = pickerSnapHelper?.findSnapView(lm)
                if (view != null) {
                    val pos = lm.getPosition(view)
                    if (pos < items.size - 1) rv.smoothScrollToPosition(pos + 1)
                }
            }
            
            var lastSnappedPos = validIndex

            rv.clearOnScrollListeners()
            rv.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                    val centerX = rv.width / 2f
                    for (i in 0 until rv.childCount) {
                        val child = rv.getChildAt(i)
                        val childCenterX = child.left + child.width / 2f
                        val distance = Math.abs(centerX - childCenterX)
                        val fraction = 1f - Math.min(1f, distance / child.width)
                        
                        val alpha = 0.4f + 0.6f * fraction
                        child.alpha = alpha
                        
                        val scale = 0.8f + 0.2f * fraction
                        child.scaleX = scale
                        child.scaleY = scale
                    }
                }

                override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                    if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) {
                        val view = pickerSnapHelper?.findSnapView(rv.layoutManager)
                        if (view != null) {
                            val pos = rv.getChildAdapterPosition(view)
                            if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                                if (pos != lastSnappedPos) {
                                    recyclerView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                    lastSnappedPos = pos
                                    onItemSelected(pos)
                                }
                            }
                        }
                    }
                }
            })
            // trigger initial alpha without firing scroll event
            rv.post {
                val centerX = rv.width / 2f
                for (i in 0 until rv.childCount) {
                    val child = rv.getChildAt(i)
                    val childCenterX = child.left + child.width / 2f
                    val distance = Math.abs(centerX - childCenterX)
                    val fraction = 1f - Math.min(1f, distance / child.width)
                    child.alpha = 0.4f + 0.6f * fraction
                    val scale = 0.8f + 0.2f * fraction
                    child.scaleX = scale
                    child.scaleY = scale
                }
            }
        }
        
        if (rv.width > 0) {
            setupLogic()
        } else {
            rv.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (rv.width > 0) {
                        rv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        setupLogic()
                    }
                }
            })
        }
    }

    private fun populateIsoOptions(rvPicker: androidx.recyclerview.widget.RecyclerView) {
        val isoList = mutableListOf<String>()
        isoList.add("AUTO")
        val stdList = ExposureHelper.getStandardIsoRange(currentMinIso, currentMaxIso)
        isoList.addAll(stdList.map { it.toString() })

        val currentIsoIndex = if (cameraHelper.manualIso == null) 0 else isoList.indexOf(cameraHelper.manualIso.toString()).coerceAtLeast(0)

        setupCarouselPicker(rvPicker, isoList, currentIsoIndex) { pos ->
            val isoStr = isoList[pos]
            val settings = SettingsManager.getInstance(this)
            if (isoStr == "AUTO") {
                settings.isIsoAuto = true
                cameraHelper.updateManualIso(null)
                binding.tvIso.text = "ISO AUTO"
                binding.quickSettings.tvIsoQuickVal.text = "AUTO"
            } else {
                settings.isIsoAuto = false
                settings.manualIsoValue = isoStr.toInt()
                cameraHelper.updateManualIso(isoStr.toInt())
                binding.tvIso.text = "ISO $isoStr"
                binding.quickSettings.tvIsoQuickVal.text = isoStr
            }
        }
    }

    private fun populateShutterOptions(rvPicker: androidx.recyclerview.widget.RecyclerView) {
        val shutterList = mutableListOf<String>()
        shutterList.add("AUTO")
        val stdShutters = listOf("1/8000", "1/6400", "1/5000", "1/4000", "1/3200", "1/2500", "1/2000", "1/1600", "1/1250", "1/1000", "1/800", "1/640", "1/500", "1/400", "1/320", "1/250", "1/200", "1/160", "1/125", "1/100", "1/80", "1/60", "1/50", "1/40", "1/30", "1/25", "1/20", "1/15", "1/13", "1/10", "1/8", "1/6", "1/5", "1/4", "1/3", "0.4\"", "0.5\"", "0.6\"", "0.8\"", "1\"", "1.3\"", "1.6\"", "2\"", "2.5\"", "3.2\"", "4\"", "5\"", "6\"", "8\"", "10\"", "13\"", "15\"", "20\"", "25\"", "30\"")
        val stdShutterNs = listOf(125000L, 156250L, 200000L, 250000L, 312500L, 400000L, 500000L, 625000L, 800000L, 1000000L, 1250000L, 1562500L, 2000000L, 2500000L, 3125000L, 4000000L, 5000000L, 6250000L, 8000000L, 10000000L, 12500000L, 16666667L, 20000000L, 25000000L, 33333333L, 40000000L, 50000000L, 66666667L, 76923077L, 100000000L, 125000000L, 16666667L, 200000000L, 250000000L, 333333333L, 400000000L, 500000000L, 625000000L, 769230769L, 1000000000L, 1300000000L, 1600000000L, 2000000000L, 2500000000L, 3200000000L, 4000000000L, 5000000000L, 6000000000L, 8000000000L, 10000000000L, 13000000000L, 15000000000L, 20000000000L, 25000000000L, 30000000000L)
        shutterList.addAll(stdShutters)

        val currentShutterIndex = if (cameraHelper.manualShutter == null) 0 else {
            val idx = stdShutterNs.indexOf(cameraHelper.manualShutter)
            if (idx >= 0) idx + 1 else 0
        }

        setupCarouselPicker(rvPicker, shutterList, currentShutterIndex) { pos ->
            val shutterStr = shutterList[pos]
            val settings = SettingsManager.getInstance(this)
            if (shutterStr == "AUTO") {
                settings.isShutterAuto = true
                cameraHelper.updateManualShutter(null)
                binding.tvShutter.text = "S AUTO"
                binding.quickSettings.tvShutterQuickVal.text = "AUTO"
            } else {
                settings.isShutterAuto = false
                settings.manualShutterValue = stdShutterNs[pos - 1]
                cameraHelper.updateManualShutter(stdShutterNs[pos - 1])
                binding.tvShutter.text = shutterStr
                binding.quickSettings.tvShutterQuickVal.text = shutterStr
            }
        }
    }

    private fun populateFormatOptions(container: android.widget.LinearLayout) {
        val formats = listOf("RAW", "JPEG", "RAW+JPEG")
        val settings = SettingsManager.getInstance(this)
        
        for (format in formats) {
            val item = layoutInflater.inflate(R.layout.item_manual_option, container, false)
            val iconView = item.findViewById<android.widget.ImageView>(R.id.item_icon)
            val textView = item.findViewById<android.widget.TextView>(R.id.item_text)
            
            iconView.visibility = View.GONE
            textView.text = format
            textView.textSize = 12f
            
            if (settings.photoFormat == format) {
                textView.setTextColor(android.graphics.Color.parseColor("#FF9800"))
            } else {
                textView.setTextColor(android.graphics.Color.WHITE)
            }
            
            item.setOnClickListener {
                settings.photoFormat = format
                binding.quickSettings.tvFormatQuickVal.text = format
                closeSettingsPanel()
            }
            container.addView(item)
        }
    }

    private fun populateFlashOptions(container: android.widget.LinearLayout) {
        val icons = listOf(R.drawable.ic_flash_off, R.drawable.ic_flash_on, R.drawable.flashlight_on)
        val texts = listOf("Tắt", "Bật", "Đèn pin")
        val modes = listOf(0, 1, 2)
        val settings = SettingsManager.getInstance(this)
        
        for (i in icons.indices) {
            val item = layoutInflater.inflate(R.layout.item_manual_option, container, false)
            val iconView = item.findViewById<android.widget.ImageView>(R.id.item_icon)
            val textView = item.findViewById<android.widget.TextView>(R.id.item_text)
            
            iconView.setImageResource(icons[i])
            textView.text = texts[i]
            
            if (settings.flashMode == modes[i]) {
                textView.setTextColor(android.graphics.Color.parseColor("#FF9800"))
                iconView.setColorFilter(android.graphics.Color.parseColor("#FF9800"))
            } else {
                textView.setTextColor(android.graphics.Color.WHITE)
                iconView.setColorFilter(android.graphics.Color.WHITE)
            }
            
            item.setOnClickListener {
                settings.flashMode = modes[i]
                cameraHelper.updateFlashMode(modes[i])
                binding.quickSettings.ivFlashQuickIcon.setImageResource(icons[i])
                closeSettingsPanel()
            }
            container.addView(item)
        }
    }

    private fun populateDriveModeOptions(container: android.widget.LinearLayout) {
        val icons = try {
            listOf(R.drawable.square, R.drawable.burst, R.drawable._10s_count, R.drawable._3s_count)
        } catch (e: Exception) {
            listOf(R.drawable.square, R.drawable.square, R.drawable.square, R.drawable.square)
        }
        val texts = listOf("Chụp từng ảnh", "Chụp liên tục", "Hẹn giờ 10s", "Hẹn giờ 3s")
        val modes = listOf(0, 1, 2, 3)
        val settings = SettingsManager.getInstance(this)
        
        for (i in icons.indices) {
            val item = layoutInflater.inflate(R.layout.item_manual_option, container, false)
            val iconView = item.findViewById<android.widget.ImageView>(R.id.item_icon)
            val textView = item.findViewById<android.widget.TextView>(R.id.item_text)
            
            iconView.setImageResource(icons[i])
            textView.text = texts[i]
            
            if (settings.driveMode == modes[i]) {
                textView.setTextColor(android.graphics.Color.parseColor("#FF9800"))
                iconView.setColorFilter(android.graphics.Color.parseColor("#FF9800"))
            } else {
                textView.setTextColor(android.graphics.Color.WHITE)
                iconView.setColorFilter(android.graphics.Color.WHITE)
            }
            
            item.setOnClickListener {
                settings.driveMode = modes[i]
                if (modes[i] == 1) {
                    android.widget.Toast.makeText(this, "Chụp liên tục: JPEG ~10fps, RAW ~5fps", android.widget.Toast.LENGTH_SHORT).show()
                }
                val iconViewQuick = binding.quickSettings.btnExtraQuick.getChildAt(0)
                iconViewQuick.setBackgroundResource(icons[i])
                closeSettingsPanel()
            }
            container.addView(item)
        }
    }

    override fun onResume() {
        super.onResume()
        cameraHelper.startBackgroundThread()
        if (binding.previewArea.isAvailable) {
            cameraHelper.openCamera(binding.previewArea)
        }
        startInfoBarUpdates()
        orientationEventListener?.enable()
        updateDisp()
    }

    override fun onPause() {
        stopInfoBarUpdates()
        orientationEventListener?.disable()
        cameraHelper.closeCamera()
        cameraHelper.stopBackgroundThread()
        levelSensorHelper?.stop()
        super.onPause()
    }

    inner class PickerAdapter(
        private val items: List<String>,
        private val itemWidth: Int,
        private val onItemSelected: (Int) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<PickerAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val textView: android.widget.TextView = view.findViewById(R.id.item_value_text)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.item_manual_value, parent, false)
            view.layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(
                itemWidth,
                androidx.recyclerview.widget.RecyclerView.LayoutParams.MATCH_PARENT
            )
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.textView.text = items[position]
            holder.itemView.setOnClickListener {
                onItemSelected(position)
            }
        }

        override fun getItemCount() = items.size
    }
}
