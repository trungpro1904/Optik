import re

file_path = r'd:\Optik\app\src\main\java\com\example\optik\BasicActivity.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

content = re.sub(r'    private var dispState = 3\n\n    // Level Sensor\n    private var levelSensorHelper: com\.example\.optik\.camera\.LevelSensorHelper\? = null\n', '', content)
content = re.sub(r'    private var isFlashOn = false\n    private var dispState = 3\n', '    private var isFlashOn = false\n', content)

old_oncreate = '''        val levelPitch = findViewById<com.example.optik.view.LevelIndicatorView>(R.id.level_pitch)
        val levelRoll = findViewById<com.example.optik.view.LevelIndicatorView>(R.id.level_roll)
        val settings = com.example.optik.settings.SettingsManager.getInstance(this@BasicActivity)
        if (settings.isVibrationEnabled) {
            levelSensorHelper = com.example.optik.camera.LevelSensorHelper(this) { pitch, roll ->
                val isPitchLevel = pitch in -2f..2f
                val isRollLevel = roll in -2f..2f
                levelPitch?.updateAngle(pitch, isPitchLevel)
                levelRoll?.updateAngle(roll, isRollLevel)
            }
            levelSensorHelper?.start()
        }
        
        setupUI()
        setupLutSelector()
        setupModeSelector()
        setupZoomControls(emptyList())
        setupOrientationListener()'''
new_oncreate = '''        setupUI()
        setupCamera()
        setupOrientationListener()'''
content = content.replace(old_oncreate, new_oncreate)

old_switch = '''    private fun switchMode(isVideo: Boolean) {
        if (isSwitchingMode) return
        isSwitchingMode = true
        isVideoModeActive = isVideo
        binding.previewBlurOverlay.visibility = View.VISIBLE
        binding.previewBlurOverlay.alpha = 0f
        // Scroll RecyclerView automatically
        val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.video_photo_selector)
        rv?.smoothScrollToPosition(if (isVideo) 0 else 1)
        
        val btnFps = binding.topBar.findViewById<TextView>(R.id.btn_fps)
        val btnRes = binding.topBar.findViewById<TextView>(R.id.btn_resolution)
        if (isVideo) {
            btnFps?.visibility = View.VISIBLE
            btnRes?.text = "HD"
            binding.shutterBg.setBackgroundResource(R.drawable.bg_shutter_video)
            binding.tvRec.visibility = View.VISIBLE
        } else {
            btnFps?.visibility = View.GONE
            btnRes?.text = "12mp"
            binding.shutterBg.setBackgroundResource(R.drawable.circle_white)
            binding.tvRec.visibility = View.GONE
            isRecording = false
            updateRecordingUI()
        }
        
        cameraHelper.setExposureCompensation(0f)
        cameraHelper.closeCamera()
        binding.root.postDelayed({
            cameraHelper.openCamera(binding.previewArea)
            isSwitchingMode = false
            binding.previewBlurOverlay.animate().alpha(0f).setDuration(300).withEndAction { binding.previewBlurOverlay.visibility = View.GONE }.start()
        }, 400)
    }'''

new_switch = '''    private fun switchMode(isVideo: Boolean) {
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
        binding.videoPhotoSelector.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        val btnVideo = binding.btnVideoMode; val btnPhoto = binding.btnPhotoMode
        val btnFps = binding.topBar.findViewById<TextView>(R.id.btn_fps); val btnRes = binding.topBar.findViewById<TextView>(R.id.btn_resolution)
        val set = androidx.constraintlayout.widget.ConstraintSet(); set.clone(binding.videoPhotoSelector)
        if (isVideo) {
            btnVideo.alpha = 1.0f; btnVideo.typeface = android.graphics.Typeface.DEFAULT_BOLD
            btnPhoto.alpha = 0.5f; btnPhoto.typeface = android.graphics.Typeface.DEFAULT
            set.connect(btnVideo.id, androidx.constraintlayout.widget.ConstraintSet.START, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.START)
            set.connect(btnVideo.id, androidx.constraintlayout.widget.ConstraintSet.END, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.END)
            set.clear(btnPhoto.id, androidx.constraintlayout.widget.ConstraintSet.START)
            set.clear(btnPhoto.id, androidx.constraintlayout.widget.ConstraintSet.END)
            set.connect(btnPhoto.id, androidx.constraintlayout.widget.ConstraintSet.START, btnVideo.id, androidx.constraintlayout.widget.ConstraintSet.END, (30 * resources.displayMetrics.density).toInt())
            btnFps?.visibility = View.VISIBLE; btnRes?.text = "HD"
            
            binding.shutterBg.setBackgroundResource(R.drawable.bg_shutter_video)
            binding.tvRec.visibility = View.VISIBLE
        } else {
            btnPhoto.alpha = 1.0f; btnPhoto.typeface = android.graphics.Typeface.DEFAULT_BOLD
            btnVideo.alpha = 0.5f; btnVideo.typeface = android.graphics.Typeface.DEFAULT
            set.connect(btnPhoto.id, androidx.constraintlayout.widget.ConstraintSet.START, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.START)
            set.connect(btnPhoto.id, androidx.constraintlayout.widget.ConstraintSet.END, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.END)
            set.clear(btnVideo.id, androidx.constraintlayout.widget.ConstraintSet.START)
            set.clear(btnVideo.id, androidx.constraintlayout.widget.ConstraintSet.END)
            set.connect(btnVideo.id, androidx.constraintlayout.widget.ConstraintSet.END, btnPhoto.id, androidx.constraintlayout.widget.ConstraintSet.START, (30 * resources.displayMetrics.density).toInt())
            btnFps?.visibility = View.GONE; btnRes?.text = "12mp"
            
            binding.shutterBg.setBackgroundResource(R.drawable.circle_white)
            binding.tvRec.visibility = View.GONE
            isRecording = false
            updateRecordingUI()
        }
        
        androidx.transition.TransitionManager.beginDelayedTransition(binding.videoPhotoSelector); set.applyTo(binding.videoPhotoSelector)
    }'''
content = content.replace(old_switch, new_switch)

content = re.sub(r'    private fun setupLutSelector\(\) \{.*?(?=    private var currentSelectedLensBtn)', '', content, flags=re.DOTALL)

old_listeners = '''        // Mode switching is now handled by the RecyclerView
        
        // updateModeUI is removed
        
        val btnMode = binding.topBar.findViewById<TextView>(R.id.btn_mode)'''

new_listeners = '''        binding.btnVideoMode.setOnClickListener { switchMode(true) }
        binding.btnPhotoMode.setOnClickListener { switchMode(false) }

        var startX = 0f
        binding.videoPhotoSelector.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> { startX = event.x; true }
                android.view.MotionEvent.ACTION_UP -> {
                    val deltaX = event.x - startX
                    if (Math.abs(deltaX) > 100) { if (deltaX > 0 && isVideoMode()) switchMode(false) else if (deltaX < 0 && !isVideoMode()) switchMode(true) }
                    v.performClick(); true
                }
                else -> false
            }
        }
        
        updateModeUI(false)
        
        val btnMode = binding.topBar.findViewById<TextView>(R.id.btn_mode)'''

content = content.replace(old_listeners, new_listeners)
content = re.sub(r'        binding\.topBar\.findViewById<android\.view\.View>\(R\.id\.btn_disp\)\?\.setOnClickListener \{.*?\}\n        \n', '', content, flags=re.DOTALL)
content = re.sub(r'    private fun updateDisp\(\) \{.*?\n    \}\n\n', '', content, flags=re.DOTALL)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)
