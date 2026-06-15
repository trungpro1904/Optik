@file:Suppress("DEPRECATION")
package com.example.optik.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.io.OutputStream
import java.io.File
import android.location.LocationManager
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import androidx.core.content.ContextCompat
import com.example.optik.settings.SettingsManager
import android.view.OrientationEventListener
import org.json.JSONObject
import org.json.JSONArray

data class CameraLens(
    val id: String,
    val focalLength: Float,
    var zoomRatio: Float = 1.0f,
    var focalLength35mm: Int = 0
)

class CameraManagerHelper(private val context: Context) {

    private val cameraManager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    
    private val glVideoProcessor = GlVideoProcessor(context)
    
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var imageReader: ImageReader? = null
    private var pictureReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    
    private val rawResultsMap = java.util.concurrent.ConcurrentHashMap<Long, TotalCaptureResult>()
    private val rawImagesMap = java.util.concurrent.ConcurrentHashMap<Long, android.media.Image>()
    
    var onImageAvailable: ((android.media.Image) -> Unit)? = null
    var onPictureSaved: ((Boolean) -> Unit)? = null
    var onCaptureStarted: ((Long) -> Unit)? = null
    var onImageSaving: (() -> Unit)? = null
    var onThumbnailAvailable: ((android.graphics.Bitmap) -> Unit)? = null
    
    private var lastJpegOrientation = 0

    private var sensorArraySize: Rect? = null
    private var aeCompensationRange: android.util.Range<Int>? = null
    private var aeCompensationStep: android.util.Rational? = null
    
    var currentCameraId: String? = null
        private set
    private var isFrontCamera: Boolean = false
    private var isFlashOn: Boolean = false
    private var selectedLutFileName: String? = null
    
    private var lastFocusRect: android.graphics.RectF? = null
    private val MOVE_THRESHOLD = 30.0f
    private var isFocusLocked = false

    private var mediaRecorder: android.media.MediaRecorder? = null
    private var isRecording = false
    private var currentTextureView: TextureView? = null
    private var currentVideoPath: String? = null
    private var videoSize = Size(1920, 1080)
    private var videoFps = 30
    private var isProcessingYuv = false

    private var currentIso: Int = 100
    private var currentShutter: Long = 10000000L

    // Auto Exposure properties
    private var minIso: Int = 100
    private var maxIso: Int = 3200
    private var minShutter: Long = 1000000L // 1ms
    private var maxShutter: Long = 100000000L // 1/10s
    var currentEvCompensation: Float = 0f
        private set
    
    var manualIso: Int? = null
    var manualShutter: Long? = null
    var meteringMode: Int = 0 // 0: Matrix, 1: Center, 2: Spot
    
    var manualWbMode: String = "AWB"
    var manualKelvin: Int = 5500
    var manualTintAB: Int = 0
    var manualTintGM: Int = 0
    
    init {
        val settings = SettingsManager.getInstance(context)
        currentEvCompensation = settings.evCompensation
        manualWbMode = settings.manualWbMode
        manualKelvin = settings.manualKelvin
        manualTintAB = settings.manualTintAB
        manualTintGM = settings.manualTintGM
        meteringMode = settings.meteringMode
        
        if (!settings.isIsoAuto) {
            manualIso = settings.manualIsoValue
        }
        if (!settings.isShutterAuto) {
            manualShutter = settings.manualShutterValue
        }
    }
    
    var onEvCalculated: ((Float) -> Unit)? = null
    private var evCalculationCounter = 0
    
    private var orientationEventListener: OrientationEventListener? = null
    private var currentDeviceOrientation: Int = 0

    // Callbacks for UI
    var onResolutionsAvailable: ((List<Size>) -> Unit)? = null
    var onCamerasAvailable: ((List<CameraLens>) -> Unit)? = null
    var onCameraInfoAvailable: ((CameraInfo) -> Unit)? = null

    data class CameraInfo(
        val aperture: Float,       // f-number (e.g. 1.8)
        val minIso: Int,
        val maxIso: Int,
        val minShutterNs: Long,    // nanoseconds
        val maxShutterNs: Long,
        val focalLength: Float,
        val focalLength35mm: Float, // tiêu cự quy đổi 35mm
        val videoConfigs: List<VideoConfig> = emptyList()
    )

    data class VideoConfig(
        val width: Int,
        val height: Int,
        val maxFps: Int
    )

    // Expose current AE values for info bar
    fun getCurrentIso(): Int = manualIso ?: currentIso
    fun getCurrentShutterNs(): Long = manualShutter ?: currentShutter
    var currentCalculatedEv: Float = 0f
    
    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            val shutter = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            // Chỉ cập nhật auto values nếu người dùng không chọn thông số manual
            if (iso != null && manualIso == null) currentIso = iso
            if (shutter != null && manualShutter == null) currentShutter = shutter
        }
    }

    fun setSelectedLut(fileName: String?) {
        selectedLutFileName = fileName
        glVideoProcessor.setLut(fileName)
    }

    fun startBackgroundThread() {
        val thread = HandlerThread("CameraBackground").also { it.start() }
        backgroundThread = thread
        backgroundHandler = Handler(thread.looper)
        glVideoProcessor.start()
    }

    fun stopBackgroundThread() {
        glVideoProcessor.stop()
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e("CameraHelper", "Error stopping background thread", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun fetchResolutions() {
        try {
            val id = currentCameraId ?: return
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val streamConfigurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            
            val outputSizes = streamConfigurationMap?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
            onResolutionsAvailable?.invoke(outputSizes)
        } catch (e: Exception) {
            Log.e("CameraHelper", "Cannot fetch resolutions", e)
        }
    }

    fun fetchCameras() {
        try {
            val lenses = mutableListOf<CameraLens>()
            val addedIds = mutableSetOf<String>()
            val focalLengthsSeen = mutableSetOf<String>()

            fun addLensIfValid(id: String) {
                if (addedIds.contains(id)) return
                try {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                        // Filter out logical multi-cameras (virtual options)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                            if (caps != null && caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
                                return
                            }
                        }
                        // Filter out ToF/IR sensors (no aperture or 0 aperture, no ISO range)
                        val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                        if (apertures != null && apertures.isNotEmpty() && apertures[0] <= 0f) {
                            return
                        }
                        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                        if (isoRange == null) {
                            return
                        }
                    
                        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        val focal = focalLengths?.firstOrNull() ?: 0f
                        
                        // Use string representation with 1 decimal to identify unique physical sensors
                        val focalStr = String.format(java.util.Locale.US, "%.1f", focal)
                        if (focalLengthsSeen.contains(focalStr) && focal > 0) return
                        
                        val lens = CameraLens(id, focal)
                        val sensorPhysicalSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                        if (sensorPhysicalSize != null && focal > 0f) {
                            val sensorW = sensorPhysicalSize.width
                            val sensorH = sensorPhysicalSize.height
                            val sensorDiag = Math.sqrt((sensorW * sensorW + sensorH * sensorH).toDouble()).toFloat()
                            val cropFactor = 43.27f / sensorDiag
                            lens.focalLength35mm = (focal * cropFactor).toInt()
                        } else {
                            // Fallback if physical size is missing
                            lens.focalLength35mm = (focal * 5.6f).toInt() 
                        }
                        
                        lenses.add(lens)
                        addedIds.add(id)
                        focalLengthsSeen.add(focalStr)
                        Log.d("CameraHelper", "Added unique lens ID: $id, focal: $focal, 35mm: ${lens.focalLength35mm}")
                    }
                } catch (e: Exception) {
                    Log.e("CameraHelper", "Error checking camera ID: $id", e)
                }
            }

            // 1. Quét danh sách camera logic chính
            val logicalIds = cameraManager.cameraIdList
            for (id in logicalIds) {
                addLensIfValid(id)
                
                // 2. Quét các camera vật lý bên trong (đối với hệ thống Multi-camera)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val physicalIds = chars.physicalCameraIds
                    for (pId in physicalIds) {
                        addLensIfValid(pId)
                    }
                }
            }
            
            if (lenses.isEmpty()) return
            
            // Sắp xếp theo tiêu cự để nút Zoom hiển thị đúng thứ tự
            lenses.sortBy { it.focalLength }
            
            // Lấy tối đa 3 ống kính chính: Ultra Wide, Wide, Tele
            val resultLenses = if (lenses.size > 3) {
                val sorted = lenses.sortedBy { it.focalLength }
                val selected = mutableListOf<CameraLens>()
                
                // Luôn lấy cái nhỏ nhất (Ultra Wide)
                selected.add(sorted.first())
                
                // Luôn lấy cái lớn nhất (Tele)
                selected.add(sorted.last())
                
                // Tìm cái gần với 24-26mm nhất (Wide chính) mà chưa được chọn
                val mainLens = sorted.filter { it !in selected }
                    .minByOrNull { Math.abs(it.focalLength35mm - 24) }
                
                if (mainLens != null) {
                    selected.add(mainLens)
                }
                
                selected.sortBy { it.focalLength }
                selected
            } else {
                lenses
            }
            
            // Tính toán zoomRatio dựa trên tiêu cự 35mm của ống kính 1x (Wide chính, thường ~24-26mm)
            val standardLens = resultLenses.minByOrNull { Math.abs(it.focalLength35mm - 24) } ?: resultLenses[0]
            val standardFocalLength35mm = if (standardLens.focalLength35mm > 0) standardLens.focalLength35mm.toFloat() else 24f
            
            for (lens in resultLenses) {
                lens.zoomRatio = if (lens.focalLength35mm > 0) lens.focalLength35mm.toFloat() / standardFocalLength35mm else 1.0f
                // Round to 1 decimal place
                lens.zoomRatio = Math.round(lens.zoomRatio * 10) / 10f
            }
            
            onCamerasAvailable?.invoke(resultLenses)
            
            if (currentCameraId == null) {
                currentCameraId = standardLens.id
            }
        } catch (e: Exception) {
            Log.e("CameraHelper", "Cannot fetch cameras", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun openCamera(textureView: TextureView, cameraId: String? = null) {
        currentTextureView = textureView
        try {
            if (cameraId != null) {
                currentCameraId = cameraId
            }
            fetchCameras() 

            
            val id = currentCameraId ?: return

            val characteristics = cameraManager.getCameraCharacteristics(id)
            sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            aeCompensationRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            aeCompensationStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            
            isFrontCamera = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT

            val apertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val sensitivityRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val sensorPhysicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            
            val aperture = apertures?.firstOrNull() ?: 0f
            val focalLength = focalLengths?.firstOrNull() ?: 0f
            val sMinIso = sensitivityRange?.lower ?: 100
            val sMaxIso = sensitivityRange?.upper ?: 3200
            val sMinShutter = exposureTimeRange?.lower ?: 1000000L
            val sMaxShutter = exposureTimeRange?.upper ?: 1000000000L

            val focalLength35mm = if (sensorPhysicalSize != null && focalLength > 0f) {
                val sensorW = sensorPhysicalSize.width
                val sensorH = sensorPhysicalSize.height
                val sensorDiag = Math.sqrt((sensorW * sensorW + sensorH * sensorH).toDouble()).toFloat()
                val cropFactor = 43.27f / sensorDiag
                focalLength * cropFactor
            } else {
                0f
            }

            val videoConfigs = mutableListOf<VideoConfig>()
            val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            
            // Lấy thông tin năng lực của bộ mã hóa Video H.264 (AVC)
            val codecList = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            val avcCodecInfo = codecList.codecInfos.firstOrNull { it.isEncoder && it.supportedTypes.contains(android.media.MediaFormat.MIMETYPE_VIDEO_AVC) }
            val videoCaps = avcCodecInfo?.getCapabilitiesForType(android.media.MediaFormat.MIMETYPE_VIDEO_AVC)?.videoCapabilities

            configMap?.getOutputSizes(android.media.MediaRecorder::class.java)?.forEach { size ->
                val duration1 = configMap.getOutputMinFrameDuration(android.media.MediaRecorder::class.java, size)
                val duration2 = configMap.getOutputMinFrameDuration(android.graphics.SurfaceTexture::class.java, size)
                val secondsPerFrame = Math.max(duration1, duration2)
                var sensorMaxFps = if (secondsPerFrame > 0) (1.0 / (secondsPerFrame / 1_000_000_000.0)).toInt() else 30
                
                var encoderMaxFps = 30
                try {
                    if (videoCaps != null && videoCaps.isSizeSupported(size.width, size.height)) {
                        val supportedFrameRates = videoCaps.getSupportedFrameRatesFor(size.width, size.height)
                        encoderMaxFps = supportedFrameRates.upper.toInt()
                    }
                } catch (e: Exception) {
                    encoderMaxFps = 30
                }
                
                // Lấy giá trị nhỏ nhất giữa khả năng của cảm biến và giới hạn của bộ mã hóa
                val realMaxFps = Math.min(sensorMaxFps, encoderMaxFps)
                
                videoConfigs.add(VideoConfig(size.width, size.height, realMaxFps))
            }

            onCameraInfoAvailable?.invoke(CameraInfo(
                aperture = aperture,
                minIso = sMinIso,
                maxIso = sMaxIso,
                minShutterNs = sMinShutter,
                maxShutterNs = sMaxShutter,
                focalLength = focalLength,
                focalLength35mm = focalLength35mm,
                videoConfigs = videoConfigs
            ))

            fetchResolutions()

            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCameraPreviewSession(textureView)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)

            if (orientationEventListener == null) {
                orientationEventListener = object : OrientationEventListener(context) {
                    override fun onOrientationChanged(orientation: Int) {
                        if (orientation == ORIENTATION_UNKNOWN) return
                        currentDeviceOrientation = ((orientation + 45) / 90 * 90) % 360
                    }
                }
            }
            orientationEventListener?.enable()

            // Log camera and device info in JSON format
            logCameraMetadata(id, characteristics)

        } catch (e: Exception) {
            Log.e("CameraHelper", "Failed to set exposure compensation", e)
        }
    }



    private fun updateCropRegion(builder: CaptureRequest.Builder, ratioStr: String) {
        val sensorRect = sensorArraySize ?: return
        val targetRatio = when (ratioStr) {
            "16:9" -> 16f / 9f
            "1:1" -> 1f
            else -> 4f / 3f
        }

        val sensorWidth = sensorRect.width()
        val sensorHeight = sensorRect.height()
        val sensorRatio = sensorWidth.toFloat() / sensorHeight

        var cropW = sensorWidth
        var cropH = sensorHeight

        if (sensorRatio > targetRatio) {
            cropW = (sensorHeight * targetRatio).toInt()
        } else {
            cropH = (sensorWidth / targetRatio).toInt()
        }

        val left = (sensorWidth - cropW) / 2
        val top = (sensorHeight - cropH) / 2
        val cropRect = Rect(left, top, left + cropW, top + cropH)
        
        builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
    }

    private fun applyCurrentSettings() {
        val builder = captureRequestBuilder ?: return
        
        val settings = SettingsManager.getInstance(context)
        val isManualMode = settings.lastUsedMode == 1
        
        if (isManualMode && settings.focusMode == 1) { // MF
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, settings.manualFocusDistance)
        } else { // AF
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        
        // Manual WB
        if (!isManualMode || manualWbMode == "AWB") {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            val gains = com.example.optik.settings.WhitebalanceHelper.colorTemperatureToRggb(manualKelvin, manualTintAB, manualTintGM)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
            builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, android.hardware.camera2.params.ColorSpaceTransform(intArrayOf(
                1, 1, 0, 1, 0, 1,
                0, 1, 1, 1, 0, 1,
                0, 1, 0, 1, 1, 1
            )))
        }
        
        if (isManualMode && manualIso != null && manualShutter != null) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso)
            // Giới hạn thời gian phơi sáng của preview tối đa 1/30s để không bị đứng khung hình
            val previewShutter = manualShutter!!.coerceAtMost(33_333_333L)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, previewShutter)
        } else {
            val settings = SettingsManager.getInstance(context)
            when (settings.flashMode) {
                1 -> { // Bật
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                }
                2 -> { // Đèn pin (Torch)
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                }
                else -> { // Tắt
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
            }
            if (isManualMode && manualIso != null) {
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso)
            }
            if (isManualMode && manualShutter != null) {
                val previewShutter = manualShutter!!.coerceAtMost(33_333_333L)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, previewShutter)
            }
        }
        
        val sensorRect = sensorArraySize
        if (sensorRect != null) {
            val actualMeteringMode = if (isManualMode) meteringMode else 0
            val meteringRect = when (actualMeteringMode) {
                2 -> { // Spot (10% center)
                    val w = sensorRect.width() / 10
                    val h = sensorRect.height() / 10
                    Rect(sensorRect.centerX() - w/2, sensorRect.centerY() - h/2, sensorRect.centerX() + w/2, sensorRect.centerY() + h/2)
                }
                1 -> { // Center (30% center)
                    val w = (sensorRect.width() * 0.3f).toInt()
                    val h = (sensorRect.height() * 0.3f).toInt()
                    Rect(sensorRect.centerX() - w/2, sensorRect.centerY() - h/2, sensorRect.centerX() + w/2, sensorRect.centerY() + h/2)
                }
                else -> sensorRect // Matrix
            }
            val meteringArray = arrayOf(MeteringRectangle(meteringRect, MeteringRectangle.METERING_WEIGHT_MAX))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, meteringArray)
        }
        
        val range = aeCompensationRange
        val step = aeCompensationStep
        if (range != null && step != null) {
            val evToApply = if (isManualMode) currentEvCompensation else 0f
            val compensation = (evToApply / step.toFloat()).toInt()
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, compensation.coerceIn(range.lower, range.upper))
        }
        
        val cameraId = currentCameraId
        if (cameraId != null) {
            try {
                val chars = cameraManager.getCameraCharacteristics(cameraId)
                val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                var bestRange: android.util.Range<Int>? = null
                if (fpsRanges != null) {
                    if (isRecording) {
                        bestRange = fpsRanges.firstOrNull { it.lower == videoFps && it.upper == videoFps }
                        if (bestRange == null) {
                            bestRange = fpsRanges.filter { it.upper == videoFps }.maxByOrNull { it.lower }
                        }
                    } else {
                        val minFps = if (fpsRanges.any { it.lower == 15 }) 15 else 30
                        bestRange = fpsRanges.filter { it.upper >= 30 && it.lower >= minFps }.minByOrNull { it.lower } ?: fpsRanges[0]
                    }
                }
                if (bestRange != null) {
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestRange)
                }
            } catch (e: Exception) {
                Log.e("CameraHelper", "Failed to set FPS range", e)
            }
        }
    }

    fun bindUseCasesWithFps(fps: Int) {
        videoFps = fps
        applyCurrentSettings()
        
        try {
            captureRequestBuilder?.let {
                val request = it.build()
                captureSession?.setRepeatingRequest(request, null, backgroundHandler)
            }
        } catch (e: Exception) {
            Log.e("CameraHelper", "Error applying FPS request", e)
        }
    }

    fun setExposureCompensation(ev: Float) {
        currentEvCompensation = ev
        SettingsManager.getInstance(context).evCompensation = ev
        val builder = captureRequestBuilder ?: return
        val range = aeCompensationRange ?: return
        val step = aeCompensationStep ?: return
        
        val compensation = (ev / step.toFloat()).toInt()
        val clamped = compensation.coerceIn(range.lower, range.upper)
        
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clamped)
        try {
            captureSession?.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e("CameraHelper", "Failed to set exposure compensation", e)
        }
    }

    private fun checkAndSaveRawMatched(timestamp: Long) {
        Log.d("CameraHelper", "checkAndSaveRawMatched: ts=$timestamp, hasResult=${rawResultsMap.containsKey(timestamp)}, hasImage=${rawImagesMap.containsKey(timestamp)}")
        val result = rawResultsMap[timestamp]
        val image = rawImagesMap[timestamp]
        if (result != null && image != null) {
            rawResultsMap.remove(timestamp)
            rawImagesMap.remove(timestamp)
            Log.d("CameraHelper", "RAW matched! Starting save thread")
            Thread {
                saveRawImage(image, result)
            }.start()
        }
    }

    private fun saveRawImage(image: android.media.Image, result: TotalCaptureResult) {
        Handler(context.mainLooper).post { onImageSaving?.invoke() }
        try {
            Log.d("CameraHelper", "saveRawImage: image format=${image.format}, width=${image.width}, height=${image.height}")
            val filename = getNextFileName(false).replace(".jpg", ".dng")
            val cameraId = currentCameraId
            if (cameraId == null) {
                Log.e("CameraHelper", "saveRawImage: currentCameraId is null")
                Handler(context.mainLooper).post { onPictureSaved?.invoke(false) }
                return
            }
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val dngCreator = android.hardware.camera2.DngCreator(chars, result)
            
            val degrees = result.get(CaptureResult.JPEG_ORIENTATION) ?: lastJpegOrientation
            val orientation = when (degrees) {
                90 -> android.media.ExifInterface.ORIENTATION_ROTATE_90
                180 -> android.media.ExifInterface.ORIENTATION_ROTATE_180
                270 -> android.media.ExifInterface.ORIENTATION_ROTATE_270
                else -> android.media.ExifInterface.ORIENTATION_NORMAL
            }
            dngCreator.setOrientation(orientation)
            
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Optik")
                }
            }
            
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            Log.d("CameraHelper", "saveRawImage: uri=$uri")
            
            if (uri != null) {
                val outputStream = resolver.openOutputStream(uri)
                if (outputStream != null) {
                    Log.d("CameraHelper", "saveRawImage: writing DNG...")
                    dngCreator.writeImage(outputStream, image)
                    outputStream.close()
                    Log.d("CameraHelper", "saveRawImage: DNG written successfully")
                } else {
                    Log.e("CameraHelper", "saveRawImage: outputStream is null")
                }
            } else {
                Log.e("CameraHelper", "saveRawImage: uri is null")
            }
            dngCreator.close()
            
            Handler(context.mainLooper).post {
                onPictureSaved?.invoke(true)
            }
        } catch (e: Exception) {
            Log.e("CameraHelper", "Error saving RAW image: ${e.message}", e)
            Handler(context.mainLooper).post {
                onPictureSaved?.invoke(false)
            }
        } finally {
            image.close()
        }
    }

    var onFocusFinished: ((Boolean) -> Unit)? = null
    private var isFocusing = false
    
    // Capture Progress Callbacks
    var onCaptureStartedListener: ((exposureTimeNs: Long) -> Unit)? = null
    var onCaptureProcessingStartedListener: (() -> Unit)? = null
    var onCaptureFinishedListener: (() -> Unit)? = null

    // For level sensor or other usage if needed

    fun focusAtPoint(x: Float, y: Float, viewWidth: Int, viewHeight: Int, isUserTouch: Boolean = false) {
        if (isFocusing && !isUserTouch) return
        
        val sensorRect = sensorArraySize ?: return
        val builder = captureRequestBuilder ?: return
        val cropRegion = builder.get(CaptureRequest.SCALER_CROP_REGION) ?: sensorRect
        
        val focusAreaSize = 200
        val halfSize = focusAreaSize / 2
        
        var normalizedX = x / viewWidth
        var normalizedY = y / viewHeight
        
        try {
            val cameraId = currentCameraId ?: return
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            if (sensorOrientation == 90) {
                val temp = normalizedX
                normalizedX = normalizedY
                normalizedY = 1f - temp
            } else if (sensorOrientation == 270) {
                val temp = normalizedX
                normalizedX = 1f - normalizedY
                normalizedY = temp
            }
        } catch (e: Exception) {}
        
        val sensorX = cropRegion.left + normalizedX * cropRegion.width()
        val sensorY = cropRegion.top + normalizedY * cropRegion.height()
        
        val rect = Rect(
            (sensorX - halfSize).toInt().coerceIn(cropRegion.left, cropRegion.right - focusAreaSize),
            (sensorY - halfSize).toInt().coerceIn(cropRegion.top, cropRegion.bottom - focusAreaSize),
            (sensorX + halfSize).toInt().coerceIn(cropRegion.left + focusAreaSize, cropRegion.right),
            (sensorY + halfSize).toInt().coerceIn(cropRegion.top + focusAreaSize, cropRegion.bottom)
        )
        
        val meteringRectangle = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)
        val meteringArray = arrayOf(meteringRectangle)
        
        builder.set(CaptureRequest.CONTROL_AF_REGIONS, meteringArray)
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, meteringArray)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
        
        isFocusLocked = true
        isFocusing = true
        lastFocusRect = android.graphics.RectF(x - 20f, y - 20f, x + 20f, y + 20f)
        
        try {
            captureSession?.setRepeatingRequest(builder.build(), null, backgroundHandler)

            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            
            captureSession?.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                    if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                        isFocusing = false
                        val success = afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                        onFocusFinished?.invoke(success)
                    }
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e("CameraHelper", "Failed to focus at point", e)
            isFocusing = false
        }
    }

    fun cancelFocus() {
        val builder = captureRequestBuilder ?: return
        val sensorRect = sensorArraySize ?: Rect(0, 0, 0, 0)
        
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
        
        val defaultMeteringArray = arrayOf(MeteringRectangle(sensorRect, 0))
        builder.set(CaptureRequest.CONTROL_AF_REGIONS, defaultMeteringArray)
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, defaultMeteringArray)
        
        try {
            captureSession?.capture(builder.build(), null, backgroundHandler)
            applyCurrentSettings()
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            isFocusLocked = false
            lastFocusRect = null
            captureSession?.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e("CameraHelper", "Failed to cancel focus", e)
        }
    }

    fun updateFocusFromTracker(newBoundingBox: android.graphics.RectF, viewWidth: Int, viewHeight: Int) {
        if (!isFocusLocked) return
        val lastRect = lastFocusRect ?: return
        
        val centerX = newBoundingBox.centerX()
        val centerY = newBoundingBox.centerY()
        val distanceX = Math.abs(centerX - lastRect.centerX())
        val distanceY = Math.abs(centerY - lastRect.centerY())
        
        if (distanceX < MOVE_THRESHOLD && distanceY < MOVE_THRESHOLD) {
            return
        }
        
        focusAtPoint(centerX, centerY, viewWidth, viewHeight)
    }

    private fun createCameraPreviewSession(textureView: TextureView) {
        try {
            val id = currentCameraId ?: return
            val characteristics = cameraManager.getCameraCharacteristics(id)
            
            val sensitivityRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            if (sensitivityRange != null) {
                minIso = sensitivityRange.lower
                maxIso = sensitivityRange.upper
            }
            val exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            if (exposureTimeRange != null) {
                minShutter = exposureTimeRange.lower
                maxShutter = exposureTimeRange.upper
            }
            
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val settings = SettingsManager.getInstance(context)
            val targetRatio = when (settings.aspectRatio) {
                "16:9" -> 16f / 9f
                "1:1" -> 1f
                else -> 4f / 3f
            }
            
            val previewSizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
            val optimalSize = previewSizes.firstOrNull { Math.abs(it.width.toFloat() / it.height - targetRatio) < 0.1 } 
                ?: previewSizes.firstOrNull() 
                ?: Size(1920, 1080)

            val texture = textureView.surfaceTexture ?: return
            texture.setDefaultBufferSize(optimalSize.width, optimalSize.height)
            val previewSurface = Surface(texture)
            
            glVideoProcessor.setDefaultBufferSize(optimalSize.width, optimalSize.height)

            if (textureView is com.example.optik.view.AutoFitTextureView) {
                Handler(context.mainLooper).post {
                    val isPortrait = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
                    if (isPortrait) {
                        textureView.setAspectRatio(optimalSize.height, optimalSize.width)
                    } else {
                        textureView.setAspectRatio(optimalSize.width, optimalSize.height)
                    }
                }
            }
            
            glVideoProcessor.setDisplaySurface(previewSurface)
            if (selectedLutFileName != null) {
                glVideoProcessor.setLut(selectedLutFileName)
            }

            // Chọn một size YUV hợp lệ thay vì hardcode 320x240
            val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
            val yuvSize = yuvSizes.firstOrNull { it.width <= 640 } ?: yuvSizes.lastOrNull() ?: Size(320, 240)

            imageReader = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 3)
            imageReader?.setOnImageAvailableListener({ reader ->
                if (isProcessingYuv) {
                    reader.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }
                
                val image = reader.acquireLatestImage()
                if (image != null) {
                    isProcessingYuv = true
                    try {
                        onImageAvailable?.invoke(image)
                        
                        if (manualIso != null && manualShutter != null) {
                            evCalculationCounter++
                            if (evCalculationCounter % 10 == 0) {
                                calculateManualEv(image)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("CameraHelper", "Error processing frame", e)
                    } finally {
                        image.close()
                        isProcessingYuv = false
                    }
                }
            }, backgroundHandler)

            val largestSize = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
                ?: Size(1920, 1080)

            val formatStr = settings.photoFormat
            var hasJpeg = formatStr.contains("JPEG")
            var hasRaw = formatStr.contains("RAW")
            
            // --- DIAGNOSTIC LOGGING ---
            Log.d("CameraHelper", "DIAGNOSTIC: Current Camera ID: $currentCameraId")
            val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            Log.d("CameraHelper", "DIAGNOSTIC: RAW capability on logical camera: ${caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val physicalIds = characteristics.physicalCameraIds
                Log.d("CameraHelper", "DIAGNOSTIC: Physical Camera IDs: $physicalIds")
                physicalIds.forEach { pid ->
                    try {
                        val pChars = cameraManager.getCameraCharacteristics(pid)
                        val pCaps = pChars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                        val pMap = pChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        val pRawSizes = pMap?.getOutputSizes(ImageFormat.RAW_SENSOR)
                        Log.d("CameraHelper", "DIAGNOSTIC: Physical ID $pid - RAW capability: ${pCaps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)}, RAW sizes: ${pRawSizes?.map{it.toString()}}")
                    } catch(e:Exception){}
                }
            }
            // --------------------------

            if (hasRaw) {
                val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
                Log.d("CameraHelper", "RAW sizes available: ${rawSizes?.map { "${it.width}x${it.height}" }}")
                if (rawSizes != null && rawSizes.isNotEmpty()) {
                    val largestRaw = rawSizes.maxByOrNull { it.width * it.height } ?: rawSizes[0]
                    Log.d("CameraHelper", "Creating rawReader: ${largestRaw.width}x${largestRaw.height}")
                    rawReader = ImageReader.newInstance(largestRaw.width, largestRaw.height, ImageFormat.RAW_SENSOR, 4)
                    rawReader?.setOnImageAvailableListener({ reader ->
                        val image = try {
                            reader.acquireNextImage()
                        } catch (e: IllegalStateException) {
                            Log.e("CameraHelper", "Max images acquired, dropping RAW frame", e)
                            null
                        }
                        if (image != null) {
                            val timestamp = image.timestamp
                            Log.d("CameraHelper", "RAW image received, timestamp=$timestamp")
                            rawImagesMap[timestamp] = image
                            checkAndSaveRawMatched(timestamp)
                            
                            // Dọn dẹp rác để tránh lỗi maxImages (2)
                            if (rawImagesMap.size > 1) {
                                val oldest = rawImagesMap.keys.minOrNull()
                                if (oldest != null && oldest != timestamp) {
                                    Log.w("CameraHelper", "Dropping unmatched RAW image: $oldest")
                                    rawImagesMap.remove(oldest)?.close()
                                }
                            }
                        } else {
                            Log.w("CameraHelper", "RAW acquireNextImage returned null")
                        }
                    }, backgroundHandler)
                } else {
                    Log.w("CameraHelper", "Camera does not support RAW_SENSOR format. Falling back to JPEG.")
                    hasRaw = false
                    hasJpeg = true
                    // Show a toast on the main thread
                    Handler(context.mainLooper).post {
                        android.widget.Toast.makeText(context, "RAW is not supported by this lens. Falling back to JPEG.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }

            if (hasJpeg || (!hasJpeg && !hasRaw)) {
                pictureReader = ImageReader.newInstance(largestSize.width, largestSize.height, ImageFormat.JPEG, 1)
                pictureReader?.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        image.close()
                        Thread {
                            saveImageBytes(bytes)
                        }.start()
                    }
                }, backgroundHandler)
            }

            val surfaces = mutableListOf<Surface>()
            if (!isRecording) {
                if (onImageAvailable != null) {
                    imageReader?.surface?.let { surfaces.add(it) }
                }
                pictureReader?.surface?.let { surfaces.add(it) }
                if (formatStr.contains("RAW")) {
                    rawReader?.surface?.let { surfaces.add(it) }
                }
            }
            
            glVideoProcessor.onInputSurfaceReady = { inputSurface ->
                surfaces.add(inputSurface)
                
                cameraDevice?.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        try {
                            val template = if (isRecording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                            captureRequestBuilder = cameraDevice?.createCaptureRequest(template)
                            captureRequestBuilder?.addTarget(inputSurface)
                            
                            if (!isRecording) {
                                if (onImageAvailable != null) {
                                    imageReader?.surface?.let { captureRequestBuilder?.addTarget(it) }
                                }
                            }
    
                            captureRequestBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            captureRequestBuilder?.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            
                            captureRequestBuilder?.let { builder -> updateCropRegion(builder, settings.aspectRatio) }
                            applyCurrentSettings()
                            
                            val request = captureRequestBuilder?.build()
                            if (request != null) {
                                session.setRepeatingRequest(request, previewCaptureCallback, backgroundHandler)
                            }
    
                            if (isRecording) {
                                mediaRecorder?.start()
                                glVideoProcessor.setRecordSurface(mediaRecorder?.surface)
                            }
                        } catch (e: Exception) {
                            Log.e("CameraHelper", "Error starting preview", e)
                        }
                    }
    
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("CameraHelper", "Configuration failed")
                    }
                }, backgroundHandler)
            }
        } catch (e: Exception) {
            Log.e("CameraHelper", "Error creating session", e)
        }
    }

    

    fun startRecording(textureView: TextureView, outputSize: Size, fps: Int): Boolean {
        if (isRecording) return false
        
        try {
            videoSize = outputSize
            videoFps = fps
            
            closeCamera()
            
            val filename = getNextFileName(true)
            
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Optik")
                }
            }
            
            val resolver = context.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw Exception("Failed to create MediaStore entry")
            
            val pfd = resolver.openFileDescriptor(uri, "w")
                ?: throw Exception("Failed to open file descriptor")
            
            currentVideoPath = uri.toString()
            
            mediaRecorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                android.media.MediaRecorder(context)
            } else {
                android.media.MediaRecorder()
            }.apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setVideoSource(android.media.MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(pfd.fileDescriptor)
                setVideoEncodingBitRate(10_000_000)
                setVideoFrameRate(fps)
                setVideoSize(outputSize.width, outputSize.height)
                setVideoEncoder(android.media.MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                
                val cameraId = currentCameraId ?: return false
                val chars = cameraManager.getCameraCharacteristics(cameraId)
                val sensorOrientation = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                val videoOrientation = if (isFrontCamera) {
                    (sensorOrientation - currentDeviceOrientation + 360) % 360
                } else {
                    (sensorOrientation + currentDeviceOrientation + 360) % 360
                }
                setOrientationHint(videoOrientation)
                glVideoProcessor.videoOrientation = videoOrientation
                prepare()
            }
            pfd.close()

            isRecording = true
            openCamera(textureView)
            return true
            
        } catch (e: Exception) {
            Log.e("CameraHelper", "Failed to start recording", e)
            isRecording = false
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null
            return false
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.e("CameraHelper", "Error stopping recorder", e)
        } finally {
            glVideoProcessor.setRecordSurface(null)
            isRecording = false
            mediaRecorder = null
            
            // Note: Since we use MediaStore, we don't need MediaScannerConnection.
            Handler(context.mainLooper).post {
                onPictureSaved?.invoke(true)
            }
            
            closeCamera()
            currentTextureView?.let { openCamera(it) }
        }
    }
    
    fun updateFlashMode(mode: Int) {
        val settings = SettingsManager.getInstance(context)
        settings.flashMode = mode
        try {
            captureRequestBuilder?.let { builder ->
                applyCurrentSettings()
                val request = builder.build()
                captureSession?.setRepeatingRequest(request, null, backgroundHandler)
            }
        } catch (e: Exception) {
            Log.e("CameraHelper", "Failed to set Flash mode", e)
        }
    }
    
    fun toggleFlash(isOn: Boolean) {
        updateFlashMode(if (isOn) 2 else 0) // 2 is Torch, 0 is Off
    }
    
    fun toggleFrontBackCamera(textureView: TextureView) {
        try {
            val facingToFind = if (isFrontCamera) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
            val id = cameraManager.cameraIdList.firstOrNull { 
                cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facingToFind 
            }
            if (id != null) {
                closeCamera()
                openCamera(textureView, id)
            }
        } catch (e: Exception) {
            Log.e("CameraHelper", "Failed to switch camera", e)
        }
    }

    fun switchLens(textureView: TextureView, cameraId: String) {
        if (currentCameraId != cameraId) {
            backgroundHandler?.post {
                closeCamera()
                // Small delay to let hardware release
                Thread.sleep(100)
                Handler(context.mainLooper).post {
                    openCamera(textureView, cameraId)
                }
            }
        }
    }

    fun captureImage() {
        try {
            val captureBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE) ?: return
            val settings = SettingsManager.getInstance(context)
            
            var targetAdded = false
            pictureReader?.surface?.let { 
                captureBuilder.addTarget(it)
                targetAdded = true
            }
            rawReader?.surface?.let { 
                captureBuilder.addTarget(it)
                targetAdded = true
            }
            
            if (!targetAdded) {
                Log.e("CameraHelper", "No surface targets available for capture!")
                Handler(context.mainLooper).post { onPictureSaved?.invoke(false) }
                return
            }
            
            val isManual = settings.lastUsedMode == 1
            
            if (isManual && (!settings.isIsoAuto || !settings.isShutterAuto)) {
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                val targetIso = manualIso ?: currentIso.coerceIn(minIso, maxIso)
                val targetShutter = manualShutter ?: currentShutter.coerceIn(minShutter, maxShutter)
                captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, targetIso)
                captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, targetShutter)
                
                if (settings.flashMode == 1) {
                    captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                } else if (settings.flashMode == 2) {
                    captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                } else {
                    captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
            } else {
                when (settings.flashMode) {
                    1 -> {
                        captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                        captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                    }
                    2 -> {
                        captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                    }
                    else -> {
                        captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    }
                }
            }
            
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, captureRequestBuilder?.get(CaptureRequest.CONTROL_AF_MODE))
            
            val cameraId = currentCameraId ?: return
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            
            // Tính toán hướng JPEG dựa trên góc xoay thực tế của máy (currentDeviceOrientation)
            val jpegOrientation = if (isFrontCamera) {
                (sensorOrientation - currentDeviceOrientation + 360) % 360
            } else {
                (sensorOrientation + currentDeviceOrientation + 360) % 360
            }
            lastJpegOrientation = jpegOrientation
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            
            if (settings.isLocationEnabled) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    if (location != null) {
                        captureBuilder.set(CaptureRequest.JPEG_GPS_LOCATION, location)
                    }
                }
            }
            
            captureSession?.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                    super.onCaptureStarted(session, request, timestamp, frameNumber)
                    Log.d("CameraHelper", "Capture started, timestamp=$timestamp")
                    val exposureNs = request.get(CaptureRequest.SENSOR_EXPOSURE_TIME) ?: currentShutter
                    var exposureMs = exposureNs / 1_000_000L
                    
                    if (!isManual) {
                        // In Auto mode, HAL handles exposure and often does multi-frame processing (taking 0.5-2s).
                        // We provide a fake duration to ensure the progress ring shows up as visual feedback.
                        exposureMs = 600L 
                    }
                    
                    Handler(context.mainLooper).post {
                        onCaptureStarted?.invoke(exposureMs)
                    }
                }
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    super.onCaptureCompleted(session, request, result)
                    Log.d("CameraHelper", "Capture completed")
                    
                    val settings = SettingsManager.getInstance(context)
                    if (settings.photoFormat.contains("RAW")) {
                        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP)
                        Log.d("CameraHelper", "RAW capture completed, SENSOR_TIMESTAMP=$ts")
                        if (ts != null) {
                            rawResultsMap[ts] = result
                            checkAndSaveRawMatched(ts)
                        } else {
                            Log.e("CameraHelper", "SENSOR_TIMESTAMP is null in capture result!")
                        }
                    }
                }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                    super.onCaptureFailed(session, request, failure)
                    Log.e("CameraHelper", "Capture FAILED, reason=${failure.reason}")
                    Handler(context.mainLooper).post {
                        onPictureSaved?.invoke(false)
                    }
                }
            }, backgroundHandler)
            
        } catch (e: Exception) {
            Log.e("CameraHelper", "Error capturing image: ${e.message}", e)
            Handler(context.mainLooper).post {
                onPictureSaved?.invoke(false)
            }
        }
    }
    
    fun captureBurst(count: Int = 10) {
        try {
            val captureBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE) ?: return
            val settings = SettingsManager.getInstance(context)
            
            var targetAdded = false
            pictureReader?.surface?.let { 
                captureBuilder.addTarget(it)
                targetAdded = true
            }
            rawReader?.surface?.let { 
                captureBuilder.addTarget(it)
                targetAdded = true
            }
            
            if (!targetAdded) return
            
            val isManual = settings.lastUsedMode == 1
            if (isManual && (!settings.isIsoAuto || !settings.isShutterAuto)) {
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                val targetIso = manualIso ?: currentIso.coerceIn(minIso, maxIso)
                val targetShutter = manualShutter ?: currentShutter.coerceIn(minShutter, maxShutter)
                captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, targetIso)
                captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, targetShutter)
            } else {
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            
            // Apply Manual WB to capture request as well
            if (manualWbMode == "AWB") {
                captureBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            } else {
                captureBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                captureBuilder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                val gains = com.example.optik.settings.WhitebalanceHelper.colorTemperatureToRggb(manualKelvin, manualTintAB, manualTintGM)
                captureBuilder.set(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
                captureBuilder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, android.hardware.camera2.params.ColorSpaceTransform(intArrayOf(
                    1, 1, 0, 1, 0, 1,
                    0, 1, 1, 1, 0, 1,
                    0, 1, 0, 1, 1, 1
                )))
            }
            
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, captureRequestBuilder?.get(CaptureRequest.CONTROL_AF_MODE))
            
            val cameraId = currentCameraId ?: return
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val jpegOrientation = if (isFrontCamera) {
                (sensorOrientation - currentDeviceOrientation + 360) % 360
            } else {
                (sensorOrientation + currentDeviceOrientation + 360) % 360
            }
            lastJpegOrientation = jpegOrientation
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            
            val request = captureBuilder.build()
            val requestList = List(count) { request }
            
            captureSession?.captureBurst(requestList, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                    super.onCaptureStarted(session, request, timestamp, frameNumber)
                    Handler(context.mainLooper).post {
                        onCaptureStarted?.invoke(100L) // fake short exposure for burst UI animation
                    }
                }
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    super.onCaptureCompleted(session, request, result)
                    if (settings.photoFormat.contains("RAW")) {
                        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP)
                        if (ts != null) {
                            rawResultsMap[ts] = result
                            checkAndSaveRawMatched(ts)
                        }
                    }
                }
            }, backgroundHandler)
            
        } catch (e: Exception) {
            Log.e("CameraHelper", "Error capturing burst", e)
            Handler(context.mainLooper).post {
                onPictureSaved?.invoke(false)
            }
        }
    }
    
    private fun getNextFileName(isVideo: Boolean): String {
        val prefix = if (isVideo) "VID_" else "IMG_"
        val extension = if (isVideo) ".mp4" else ".jpg"
        val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US)
        return prefix + sdf.format(java.util.Date()) + extension
    }

    private fun saveImageBytes(bytes: ByteArray) {
        Handler(context.mainLooper).post {
            onImageSaving?.invoke()
        }
        
        val settings = SettingsManager.getInstance(context)
        val targetRatioStr = settings.aspectRatio
        val targetRatio = when (targetRatioStr) {
            "16:9" -> 16f / 9f
            "1:1" -> 1f
            else -> 4f / 3f
        }
        
        var finalBytes = bytes
        
        val options = android.graphics.BitmapFactory.Options()
        options.inJustDecodeBounds = true
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val srcW = options.outWidth
        val srcH = options.outHeight
        
        if (srcW > 0 && srcH > 0) {
            val currentRatio = Math.max(srcW, srcH).toFloat() / Math.min(srcW, srcH)
            if (Math.abs(currentRatio - targetRatio) > 0.05f) {
                try {
                    val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        android.graphics.BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
                    } else {
                        android.graphics.BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
                    }
                    
                    if (decoder != null) {
                        val isPortrait = srcH > srcW
                        val actualTargetRatio = if (isPortrait) 1f / targetRatio else targetRatio
                        
                        var cropW = srcW
                        var cropH = srcH
                        
                        if (srcW.toFloat() / srcH > actualTargetRatio) {
                            cropW = (srcH * actualTargetRatio).toInt()
                        } else {
                            cropH = (srcW / actualTargetRatio).toInt()
                        }
                        
                        val x = (srcW - cropW) / 2
                        val y = (srcH - cropH) / 2
                        val rect = android.graphics.Rect(x, y, x + cropW, y + cropH)
                        
                        val cropped = decoder.decodeRegion(rect, android.graphics.BitmapFactory.Options())
                        if (cropped != null) {
                            val outputStream = java.io.ByteArrayOutputStream()
                            cropped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, outputStream)
                            finalBytes = outputStream.toByteArray()
                            cropped.recycle()
                        }
                        decoder.recycle()
                    }
                } catch (e: Exception) {
                    Log.e("CameraHelper", "Failed to crop image", e)
                }
            }
        }
        
        val optionsThumb = android.graphics.BitmapFactory.Options()
        optionsThumb.inSampleSize = 8
        val rawThumbnail = android.graphics.BitmapFactory.decodeByteArray(finalBytes, 0, finalBytes.size, optionsThumb)
        if (rawThumbnail != null) {
            val matrix = android.graphics.Matrix()
            matrix.postRotate(lastJpegOrientation.toFloat())
            val thumbnail = android.graphics.Bitmap.createBitmap(rawThumbnail, 0, 0, rawThumbnail.width, rawThumbnail.height, matrix, true)
            Handler(context.mainLooper).post {
                onThumbnailAvailable?.invoke(thumbnail)
            }
            if (thumbnail != rawThumbnail) rawThumbnail.recycle()
        }
        
        val filename = getNextFileName(false)
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Optik")
            }
        }
        
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        
        try {
            uri?.let {
                val outputStream = resolver.openOutputStream(it)
                if (outputStream != null) {
                    if (selectedLutFileName != null) {
                        Log.d("CameraHelper", "Applying GPU LUT: $selectedLutFileName")
                        LutProcessor.processAndSaveLut(context, finalBytes, selectedLutFileName, outputStream)
                    } else {
                        outputStream.write(finalBytes)
                    }
                    outputStream.close()
                    
                    // Update EXIF metadata
                    try {
                        val pfd = resolver.openFileDescriptor(it, "rw")
                        if (pfd != null) {
                            val exif = android.media.ExifInterface(pfd.fileDescriptor)
                            
                            // Snap ISO to standard value
                            val isoStr = exif.getAttribute(android.media.ExifInterface.TAG_ISO_SPEED_RATINGS)
                            if (isoStr != null) {
                                val iso = isoStr.toIntOrNull()
                                if (iso != null) {
                                    val stdIso = com.example.optik.settings.ExposureHelper.snapToStandardIso(iso)
                                    exif.setAttribute(android.media.ExifInterface.TAG_ISO_SPEED_RATINGS, stdIso.toString())
                                }
                            }
                            
                            // Snap Shutter to standard value
                            val shutterStr = exif.getAttribute(android.media.ExifInterface.TAG_EXPOSURE_TIME)
                            if (shutterStr != null) {
                                val shutterSec = shutterStr.toDoubleOrNull()
                                if (shutterSec != null) {
                                    val shutterNs = (shutterSec * 1_000_000_000).toLong()
                                    val stdShutter = com.example.optik.settings.ExposureHelper.formatShutterExif(shutterNs)
                                    exif.setAttribute(android.media.ExifInterface.TAG_EXPOSURE_TIME, stdShutter)
                                }
                            }
                            
                            exif.saveAttributes()
                            pfd.close()
                        }
                    } catch (ex: Exception) {
                        Log.e("CameraHelper", "Failed to update EXIF", ex)
                    }
                }
                
                Handler(context.mainLooper).post {
                    onPictureSaved?.invoke(true)
                }
            } ?: run {
                Handler(context.mainLooper).post {
                    onPictureSaved?.invoke(false)
                }
            }
        } catch (e: Exception) {
            Log.e("CameraHelper", "Failed to save image", e)
            Handler(context.mainLooper).post {
                onPictureSaved?.invoke(false)
            }
        }
    }

    fun setFocusPoint(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        val sensor = sensorArraySize ?: return
        val builder = captureRequestBuilder ?: return
        val session = captureSession ?: return

        try {
            val xRatio = x / viewWidth.toFloat()
            val yRatio = y / viewHeight.toFloat()
            
            val focusX = (xRatio * sensor.width()).toInt()
            val focusY = (yRatio * sensor.height()).toInt()
            
            val halfBox = 100
            
            val rect = Rect(
                maxOf(0, focusX - halfBox),
                maxOf(0, focusY - halfBox),
                minOf(sensor.width(), focusX + halfBox),
                minOf(sensor.height(), focusY + halfBox)
            )

            val meteringRectangle = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)
            val regions = arrayOf(meteringRectangle)

            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            session.capture(builder.build(), null, backgroundHandler)

            builder.set(CaptureRequest.CONTROL_AF_REGIONS, regions)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            session.capture(builder.build(), null, backgroundHandler)

        } catch (e: Exception) {
            Log.e("CameraHelper", "Error setting focus", e)
        }
    }

    fun setTrackingRegion(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        val sensor = sensorArraySize ?: return
        val builder = captureRequestBuilder ?: return
        val session = captureSession ?: return

        try {
            val xRatio = x / viewWidth.toFloat()
            val yRatio = y / viewHeight.toFloat()
            
            val focusX = (xRatio * sensor.width()).toInt()
            val focusY = (yRatio * sensor.height()).toInt()
            
            val halfBox = 100
            
            val rect = Rect(
                maxOf(0, focusX - halfBox),
                maxOf(0, focusY - halfBox),
                minOf(sensor.width(), focusX + halfBox),
                minOf(sensor.height(), focusY + halfBox)
            )

            val meteringRectangle = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)
            val regions = arrayOf(meteringRectangle)

            builder.set(CaptureRequest.CONTROL_AF_REGIONS, regions)
            
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            // Ignore if session is closed
        }
    }

    fun getLatestMediaUri(): android.net.Uri? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%Pictures/Optik%")
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        
        context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val id = cursor.getLong(idColumn)
                return android.content.ContentUris.withAppendedId(collection, id)
            }
        }
        return null
    }

    private fun logCameraMetadata(cameraId: String, chars: CameraCharacteristics) {
        try {
            val root = JSONObject()
            
            // 1. Device Info
            val device = JSONObject()
            device.put("brand", Build.BRAND)
            device.put("manufacturer", Build.MANUFACTURER)
            device.put("model", Build.MODEL)
            device.put("sdk", Build.VERSION.SDK_INT)
            root.put("device", device)

            // 2. Camera Characteristics
            val camera = JSONObject()
            camera.put("id", cameraId)
            
            val hardwareLevel = when(chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                else -> "UNKNOWN"
            }
            camera.put("hardware_level", hardwareLevel)

            // Sensor Info
            val sensor = JSONObject()
            sensor.put("orientation", chars.get(CameraCharacteristics.SENSOR_ORIENTATION))
            val arraySize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            sensor.put("active_array_size", "${arraySize?.width()}x${arraySize?.height()}")
            val physicalSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            sensor.put("physical_size_mm", "${physicalSize?.width}x${physicalSize?.height}")
            camera.put("sensor", sensor)

            // Exposure Ranges
            val exposure = JSONObject()
            val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            exposure.put("iso_range", "${isoRange?.lower} - ${isoRange?.upper}")
            val shutterRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            exposure.put("shutter_range_ns", "${shutterRange?.lower} - ${shutterRange?.upper}")
            val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            exposure.put("ev_compensation_range", "${evRange?.lower} - ${evRange?.upper}")
            val evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            exposure.put("ev_compensation_step", evStep?.toString())
            
            // AE Target FPS Ranges
            val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            exposure.put("ae_target_fps_ranges", JSONArray(fpsRanges?.map { "[${it.lower}, ${it.upper}]" } ?: emptyList<String>()))
            camera.put("exposure", exposure)

            // Optics
            val optics = JSONObject()
            val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            optics.put("apertures", JSONArray(apertures?.toList() ?: emptyList<Float>()))
            val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            optics.put("focal_lengths", JSONArray(focals?.toList() ?: emptyList<Float>()))
            camera.put("optics", optics)

            // Video Capabilities (High Speed)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val videoCaps = JSONObject()
            if (map != null) {
                try {
                    val hsSizes = map.highSpeedVideoSizes
                    val hsConfigs = JSONArray()
                    for (size in hsSizes) {
                        val ranges = map.getHighSpeedVideoFpsRangesFor(size)
                        val sizeObj = JSONObject()
                        sizeObj.put("size", "${size.width}x${size.height}")
                        sizeObj.put("fps_ranges", JSONArray(ranges?.map { "[${it.lower}, ${it.upper}]" } ?: emptyList<String>()))
                        hsConfigs.put(sizeObj)
                    }
                    videoCaps.put("high_speed_video_configs", hsConfigs)
                } catch (e: Exception) {
                    videoCaps.put("high_speed_video_supported", false)
                }
            }
            camera.put("video_capabilities", videoCaps)

            // Stream Configs with Max FPS calculation
            val formats = map?.outputFormats ?: intArrayOf()
            val configs = JSONArray()
            for (format in formats) {
                val formatName = when(format) {
                    ImageFormat.JPEG -> "JPEG"
                    ImageFormat.YUV_420_888 -> "YUV_420_888"
                    ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
                    ImageFormat.PRIVATE -> "PRIVATE"
                    else -> "0x${Integer.toHexString(format)}"
                }
                val sizes = map?.getOutputSizes(format)
                val formatObj = JSONObject()
                formatObj.put("format", formatName)
                
                val resolutions = JSONArray()
                sizes?.forEach { size ->
                    val minDuration = map.getOutputMinFrameDuration(format, size)
                    val maxFps = if (minDuration > 0) (1_000_000_000L / minDuration).toInt() else 0
                    
                    val resObj = JSONObject()
                    resObj.put("size", "${size.width}x${size.height}")
                    resObj.put("max_fps", maxFps)
                    resolutions.put(resObj)
                }
                formatObj.put("resolutions", resolutions)
                configs.put(formatObj)
            }
            camera.put("stream_configurations", configs)

            root.put("camera_metadata", camera)

            // 3. Print to Logcat
            Log.i("OptikHardwareLog", "\n" + root.toString(4))
            
        } catch (e: Exception) {
            Log.e("CameraHelper", "Failed to log metadata", e)
        }
    }

    fun updateFocusMode(mode: Int, distance: Float) {
        val settings = SettingsManager.getInstance(context)
        settings.focusMode = mode
        if (mode == 1) {
            settings.manualFocusDistance = distance
        }
        applyCurrentSettingsAndUpdate()
    }
    fun updateDisplaySurfaceSize() {
        glVideoProcessor.recreateDisplaySurface()
    }

    fun closeCamera() {
        orientationEventListener?.disable()
        if (isRecording) {
            stopRecording()
        }
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        pictureReader?.close()
        pictureReader = null
        rawReader?.close()
        rawReader = null
        
        rawImagesMap.values.forEach { it.close() }
        rawImagesMap.clear()
        rawResultsMap.clear()
    }

    private var yuvBytes: ByteArray? = null

    fun calculateManualEv(image: android.media.Image) {
        if (image.format != ImageFormat.YUV_420_888) return
        val yPlane = image.planes[0]
        val buffer = yPlane.buffer
        val width = image.width
        val height = image.height
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride

        val capacity = buffer.remaining()
        if (yuvBytes == null || yuvBytes!!.size < capacity) {
            yuvBytes = ByteArray(capacity)
        }
        buffer.get(yuvBytes!!, 0, capacity)

        var totalLuma: Long = 0
        var count = 0

        val startX = when(meteringMode) { 2 -> width/2 - width/20; 1 -> width/2 - (width*0.15).toInt(); else -> 0 }
        val endX = when(meteringMode) { 2 -> width/2 + width/20; 1 -> width/2 + (width*0.15).toInt(); else -> width }
        val startY = when(meteringMode) { 2 -> height/2 - height/20; 1 -> height/2 - (height*0.15).toInt(); else -> 0 }
        val endY = when(meteringMode) { 2 -> height/2 + height/20; 1 -> height/2 + (height*0.15).toInt(); else -> height }

        val step = 8 
        for (y in startY until endY step step) {
            val rowStart = y * rowStride
            for (x in startX until endX step step) {
                val index = rowStart + x * pixelStride
                if (index < capacity) {
                    val luma = yuvBytes!![index].toInt() and 0xFF
                    totalLuma += luma
                    count++
                }
            }
        }
        
        if (count > 0) {
            val avgLuma = totalLuma.toFloat() / count
            val ev = (Math.log(avgLuma.toDouble() / 118.0) / Math.log(2.0)).toFloat()
            val finalEv = ev.coerceIn(-2.0f, 2.0f)
            this.currentCalculatedEv = finalEv
            
            Handler(android.os.Looper.getMainLooper()).post {
                onEvCalculated?.invoke(finalEv)
            }
        }
    }

    fun updateManualIso(iso: Int?) {
        manualIso = iso
        applyCurrentSettingsAndUpdate()
    }
    
    fun updateManualWb(mode: String, kelvin: Int, ab: Int, gm: Int) {
        manualWbMode = mode
        manualKelvin = kelvin
        manualTintAB = ab
        manualTintGM = gm
        applyCurrentSettingsAndUpdate()
    }
    
    fun updateManualShutter(shutterNs: Long?) {
        manualShutter = shutterNs
        applyCurrentSettingsAndUpdate()
    }
    
    fun updateMeteringMode(mode: Int) {
        meteringMode = mode
        applyCurrentSettingsAndUpdate()
    }
    
    private fun applyCurrentSettingsAndUpdate() {
        val builder = captureRequestBuilder ?: return
        applyCurrentSettings()
        try {
            captureSession?.setRepeatingRequest(builder.build(), previewCaptureCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e("CameraHelper", "Error applying manual settings", e)
        }
    }
}
