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

/**
 * Bảng giá trị chuẩn nhiếp ảnh theo bước 1/3 stop.
 * Cung cấp:
 * - Danh sách tốc độ màn trập chuẩn (ns) từ 1/8000s đến 30s
 * - Danh sách ISO chuẩn từ 25 đến 102400
 * - Hàm làm tròn về giá trị chuẩn gần nhất
 * - Hàm định dạng hiển thị
 */
object ExposureHelper {

    // === TỐC ĐỘ MÀN TRẬP chuẩn (1/3 stop), đơn vị: nanoseconds ===
    // Từ 1/8000s đến 30s
    private val SHUTTER_SPEEDS_NS: LongArray = longArrayOf(
        // 1/8000 .. 1/1000
        125_000L,      // 1/8000
        156_250L,      // 1/6400
        200_000L,      // 1/5000
        250_000L,      // 1/4000
        312_500L,      // 1/3200
        400_000L,      // 1/2500
        500_000L,      // 1/2000
        625_000L,      // 1/1600
        800_000L,      // 1/1250
        1_000_000L,    // 1/1000
        // 1/800 .. 1/100
        1_250_000L,    // 1/800
        1_562_500L,    // 1/640
        2_000_000L,    // 1/500
        2_500_000L,    // 1/400
        3_125_000L,    // 1/320
        4_000_000L,    // 1/250
        5_000_000L,    // 1/200
        6_250_000L,    // 1/160
        8_000_000L,    // 1/125
        10_000_000L,   // 1/100
        // 1/80 .. 1/1
        12_500_000L,   // 1/80
        16_666_667L,   // 1/60
        20_000_000L,   // 1/50
        25_000_000L,   // 1/40
        33_333_333L,   // 1/30
        40_000_000L,   // 1/25
        50_000_000L,   // 1/20
        66_666_667L,   // 1/15
        76_923_077L,   // 1/13
        100_000_000L,  // 1/10
        125_000_000L,  // 1/8
        166_666_667L,  // 1/6
        200_000_000L,  // 1/5
        250_000_000L,  // 1/4
        333_333_333L,  // 1/3
        400_000_000L,  // 0.4"
        500_000_000L,  // 0.5"
        625_000_000L,  // 0.6"
        769_230_769L,  // 0.8"
        1_000_000_000L,  // 1"
        1_300_000_000L,  // 1.3"
        1_600_000_000L,  // 1.6"
        2_000_000_000L,  // 2"
        2_500_000_000L,  // 2.5"
        3_200_000_000L,  // 3.2"
        4_000_000_000L,  // 4"
        5_000_000_000L,  // 5"
        6_000_000_000L,  // 6"
        8_000_000_000L,  // 8"
        10_000_000_000L, // 10"
        13_000_000_000L, // 13"
        15_000_000_000L, // 15"
        20_000_000_000L, // 20"
        25_000_000_000L, // 25"
        30_000_000_000L  // 30"
    )

    // Nhãn hiển thị tương ứng
    private val SHUTTER_LABELS: Array<String> = arrayOf(
        "1/8000", "1/6400", "1/5000", "1/4000", "1/3200", "1/2500",
        "1/2000", "1/1600", "1/1250", "1/1000",
        "1/800", "1/640", "1/500", "1/400", "1/320", "1/250",
        "1/200", "1/160", "1/125", "1/100",
        "1/80", "1/60", "1/50", "1/40", "1/30", "1/25",
        "1/20", "1/15", "1/13", "1/10", "1/8", "1/6",
        "1/5", "1/4", "1/3", "0.4\"", "0.5\"", "0.6\"",
        "0.8\"", "1\"", "1.3\"", "1.6\"", "2\"", "2.5\"",
        "3.2\"", "4\"", "5\"", "6\"", "8\"", "10\"",
        "13\"", "15\"", "20\"", "25\"", "30\""
    )

    // === ISO chuẩn (1/3 stop) ===
    private val ISO_VALUES: IntArray = intArrayOf(
        25, 32, 40, 50, 64, 80, 100, 125, 160, 200,
        250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000,
        2500, 3200, 4000, 5000, 6400, 8000, 10000, 12500, 16000,
        20000, 25600, 32000, 40000, 51200, 64000, 80000, 102400
    )

    /**
     * Trả về danh sách ISO chuẩn nằm trong [sensorMin, sensorMax].
     * sensorMin được làm tròn LÊN, sensorMax được làm tròn XUỐNG.
     */
    fun getStandardIsoRange(sensorMin: Int, sensorMax: Int): List<Int> {
        val result = mutableListOf<Int>()
        for (iso in ISO_VALUES) {
            if (iso >= sensorMin && iso <= sensorMax) {
                result.add(iso)
            }
        }
        // Nếu không có giá trị nào khớp, thêm min/max gốc
        if (result.isEmpty()) {
            result.add(roundIsoUp(sensorMin))
        }
        return result
    }

    /** Làm tròn ISO LÊN giá trị chuẩn gần nhất */
    fun roundIsoUp(raw: Int): Int {
        for (iso in ISO_VALUES) {
            if (iso >= raw) return iso
        }
        return ISO_VALUES.last()
    }

    /** Làm tròn ISO XUỐNG giá trị chuẩn gần nhất */
    fun roundIsoDown(raw: Int): Int {
        for (i in ISO_VALUES.indices.reversed()) {
            if (ISO_VALUES[i] <= raw) return ISO_VALUES[i]
        }
        return ISO_VALUES.first()
    }

    /**
     * Tìm giá trị chuẩn gần nhất với giá trị ns thực tế.
     * Trả về nhãn hiển thị (ví dụ "1/250").
     */
    fun formatShutterSpeed(ns: Long): String {
        if (ns <= 0) return "--"
        // Tìm giá trị chuẩn gần nhất (theo log scale)
        var bestIdx = 0
        var bestDist = Double.MAX_VALUE
        val logNs = Math.log(ns.toDouble())
        for (i in SHUTTER_SPEEDS_NS.indices) {
            val dist = Math.abs(Math.log(SHUTTER_SPEEDS_NS[i].toDouble()) - logNs)
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = i
            }
        }
        return SHUTTER_LABELS[bestIdx]
    }

    /** Format ISO cho hiển thị, làm tròn về giá trị chuẩn gần nhất */
    fun formatIso(raw: Int): String {
        var bestIdx = 0
        var bestDist = Int.MAX_VALUE
        for (i in ISO_VALUES.indices) {
            val dist = Math.abs(ISO_VALUES[i] - raw)
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = i
            }
        }
        return ISO_VALUES[bestIdx].toString()
    }
}

class ManualActivity : AppCompatActivity() {
    private lateinit var binding: ActivityManualBinding
    private lateinit var cameraHelper: CameraManagerHelper
    private lateinit var faceTracker: com.example.optik.camera.FaceTracker
    private var touchLockedFaceCenter: android.graphics.PointF? = null
    private var isTouchFocusLocked = false
    private var isCapturing = false
    private lateinit var objectTracker: com.example.optik.camera.ObjectTracker
    private var trackedObjectId: Int? = null
    private var isTrackingFace = false
    private var latestObjects: List<com.google.mlkit.vision.objects.DetectedObject> = emptyList()
    private var maxCameraMp = 12
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private var infoBarUpdateRunnable: Runnable? = null
    private var orientationEventListener: android.view.OrientationEventListener? = null
    private var currentRotation: Float = 0f
    private var levelSensorHelper: com.example.optik.camera.LevelSensorHelper? = null
    private var dispState = 3 // 3 = both Grid and Level ON

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

        cameraHelper = CameraManagerHelper(this)
        
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

        cameraHelper.onImageAvailable = { image ->
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

        cameraHelper.onCaptureStarted = { durationMs ->
            runOnUiThread {
                if (durationMs >= 125) {
                    val progressView = findViewById<com.example.optik.view.CaptureProgressView>(R.id.capture_progress_view)
                    progressView?.startProgress(durationMs)
                }
            }
        }
        
        cameraHelper.onImageSaving = {
            runOnUiThread {
                findViewById<android.widget.ProgressBar>(R.id.album_progress)?.visibility = View.VISIBLE
            }
        }
        
        cameraHelper.onPictureSaved = { success ->
            runOnUiThread {
                isCapturing = false
                findViewById<android.widget.ProgressBar>(R.id.album_progress)?.visibility = View.GONE
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
            }
        }

        cameraHelper.onCamerasAvailable = { lenses ->
            runOnUiThread { setupLensSelector(lenses) }
        }

        cameraHelper.onResolutionsAvailable = { sizes -> 
            runOnUiThread { 
                maxCameraMp = sizes.maxOfOrNull { it.width * it.height / 1_000_000 } ?: 12
                val btnRes = binding.tvMp
                btnRes?.text = SettingsManager.getInstance(this@ManualActivity).photoResolution.ifEmpty { "12mp" }
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

    private var currentEvCompensation: Float = 0f

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
        binding.tvEv.text = String.format("%+.1f", currentEvCompensation)
        binding.quickSettings.tvEvQuickVal.text = String.format("%.1f", currentEvCompensation)
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
            if (findViewById<android.widget.ProgressBar>(R.id.album_progress)?.visibility == View.VISIBLE) {
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
            val steps = listOf(0f, 0.3f, 0.7f, 1.0f, -1.0f, -0.7f, -0.3f)
            val currentIndex = steps.indexOf(currentEvCompensation)
            val nextIndex = (currentIndex + 1) % steps.size
            currentEvCompensation = steps[nextIndex]
            cameraHelper.setExposureCompensation(currentEvCompensation)
            updateInfoBarValues()
        }

        binding.quickSettings.btnRatioQuick.setOnClickListener {
            binding.quickSettings.root.visibility = View.INVISIBLE
            binding.ratioSelectionPanel.visibility = View.VISIBLE
            updateRatioPanelUI()
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

        binding.btnShutter.setOnClickListener {
            if (isCapturing) return@setOnClickListener
            isCapturing = true
            val overlay = binding.previewBlurOverlay
            overlay.visibility = View.VISIBLE
            overlay.setBackgroundColor(android.graphics.Color.BLACK)
            overlay.alpha = 1f
            overlay.animate().alpha(0f).setDuration(300).withEndAction { 
                overlay.visibility = View.GONE
                overlay.setBackgroundColor(android.graphics.Color.parseColor("#A0000000")) 
            }.start()
            cameraHelper.captureImage()
        }
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
            binding.quickSettings.tvEvQuickVal.text = String.format("%.1f", currentEvCompensation)

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
}

