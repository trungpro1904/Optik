package com.example.optik

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.optik.camera.CameraManagerHelper
import com.example.optik.databinding.ActivitySettingsBinding
import com.example.optik.settings.SettingsBottomSheet
import com.example.optik.settings.SettingsManager

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsManager
    private lateinit var cameraHelper: CameraManagerHelper
    private var availableResolutions: List<String> = emptyList()

    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            settings.isLocationEnabled = true
        } else {
            settings.isLocationEnabled = false
            binding.rowSaveLocation.root.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.setting_switch).isChecked = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsManager.getInstance(this)
        cameraHelper = CameraManagerHelper(this)

        binding.btnClose.setOnClickListener { finish() }

        setupUI()
        fetchResolutions()
    }

    private fun setupUI() {
        // Save Location Switch
        val locationSwitch = binding.rowSaveLocation.root.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.setting_switch)
        binding.rowSaveLocation.root.findViewById<TextView>(R.id.setting_title).text = "Lưu vị trí?"
        locationSwitch.isChecked = settings.isLocationEnabled
        locationSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                } else {
                    settings.isLocationEnabled = true
                }
            } else {
                settings.isLocationEnabled = false
            }
        }

        setupRow(binding.rowPhotoFormat.root, "Định dạng ảnh mặc định", settings.photoFormat) {
            showBottomSheet("Định dạng ảnh mặc định", "Trong chế độ Basic chỉ cho phép định dạng JPEG.", listOf("JPEG", "RAW", "RAW+JPEG"), settings.photoFormat) {
                settings.photoFormat = it
                updateRowValue(binding.rowPhotoFormat.root, it)
            }
        }

        setupRow(binding.rowPhotoResolution.root, "Độ phân giải mặc định", settings.photoResolution.ifEmpty { "Đang lấy..." }) {
            if (availableResolutions.isNotEmpty()) {
                showBottomSheet("Độ phân giải mặc định", "Kích thước file ảnh tỷ lệ thuận với độ phân giải, đồng thời độ phân giải càng cao, ảnh càng chi tiết.", availableResolutions, settings.photoResolution) {
                    settings.photoResolution = it
                    updateRowValue(binding.rowPhotoResolution.root, it)
                }
            }
        }



        setupRow(binding.rowVideoFormat.root, "Định dạng video mặc định", settings.videoFormat) {
            showBottomSheet("Định dạng video mặc định", "Độ phân giải video và tốc độ khung hình tỷ lệ thuận với kích thước file video.\n*Lưu ý rằng việc quay video với độ phân giải lớn và FPS cao có thể làm nóng máy.", listOf("4K", "HD", "720p"), settings.videoFormat) {
                settings.videoFormat = it
                updateRowValue(binding.rowVideoFormat.root, it)
            }
        }

        // Grid/Ruler/Histogram Switches
        setupSwitch(binding.rowGrid.root, "Lưới", settings.isGridEnabled) { settings.isGridEnabled = it }
        setupSwitch(binding.rowRuler.root, "Thước", settings.isLevelEnabled) { settings.isLevelEnabled = it }
        setupSwitch(binding.rowHistogram.root, "Histogram", false) { }

        setupRow(binding.rowAspectRatio.root, "Tỷ lệ khung hình mặc định", settings.aspectRatio) {
            showBottomSheet("Tỷ lệ khung hình mặc định", "", listOf("4:3", "16:9", "1:1"), settings.aspectRatio) {
                settings.aspectRatio = it
                updateRowValue(binding.rowAspectRatio.root, it)
            }
        }

        setupSwitch(binding.rowHaptic.root, "Rung?", true) { }

        setupRow(binding.rowStartupMode.root, "Chế độ chụp khởi chạy", if (settings.startupMode == 0) "Luôn dùng chế độ Basic" else "Như hiện tại") {
            showBottomSheet("Chế độ chụp khởi chạy", "Như hiện tại: Khi mở app sẽ hiển thị chế độ cuối cùng bạn sử dụng.\nLuôn dùng chế độ Basic: Luôn mở chế độ Basic khi khởi động lại app.", listOf("Như hiện tại", "Luôn dùng chế độ Basic"), if (settings.startupMode == 0) "Luôn dùng chế độ Basic" else "Như hiện tại") {
                settings.startupMode = if (it == "Như hiện tại") 2 else 0
                updateRowValue(binding.rowStartupMode.root, it)
            }
        }

        setupRow(binding.rowVolumeKey.root, "Dùng phím âm lượng là", settings.volumeKeyAction) {
            showBottomSheet("Dùng phím âm lượng là", "", listOf("Zoom", "Chụp"), settings.volumeKeyAction) {
                settings.volumeKeyAction = it
                updateRowValue(binding.rowVolumeKey.root, it)
            }
        }

        setupRow(binding.rowTouchAction.root, "Chạm vào màn hình để", settings.touchAction) {
            showBottomSheet("Chạm vào màn hình để", "Dò tìm đối tượng thích hợp nhất khi muốn dõi theo chủ thể chuyển động.\n\nTiêu điểm và độ sáng cho phép máy tính toán lượng ánh sáng phù hợp cho vùng được chọn khi có 1 trong 2 thông số S hoặc ISO là AUTO.\n\nCả 2 chế độ đều cho phép tự động lấy nét", listOf("Dò tìm đối tượng", "Tiêu điểm và độ sáng"), settings.touchAction) {
                settings.touchAction = it
                updateRowValue(binding.rowTouchAction.root, it)
            }
        }

        setupSwitch(binding.rowShutterSound.root, "Âm thanh chụp", settings.isShutterSoundEnabled) { settings.isShutterSoundEnabled = it }
        
        binding.btnGuide.setOnClickListener {
            // Show guide
        }
    }

    private fun setupSwitch(view: View, title: String, isChecked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        view.findViewById<TextView>(R.id.setting_title).text = title
        val switchView = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.setting_switch)
        switchView.isChecked = isChecked
        switchView.setOnCheckedChangeListener { _, checked -> onCheckedChange(checked) }
    }

    private fun setupRow(view: View, title: String, value: String, onClick: () -> Unit) {
        view.findViewById<TextView>(R.id.tv_title).text = title
        view.findViewById<TextView>(R.id.tv_value).text = value
        view.setOnClickListener { onClick() }
    }

    private fun updateRowValue(view: View, value: String) {
        view.findViewById<TextView>(R.id.tv_value).text = value
    }

    private fun disableRow(view: View) {
        view.isEnabled = false
        view.alpha = 0.5f
    }

    private fun showBottomSheet(title: String, desc: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
        val bottomSheet = SettingsBottomSheet()
        bottomSheet.title = title
        bottomSheet.description = desc
        bottomSheet.options = options
        bottomSheet.selectedOption = selected
        bottomSheet.onOptionSelected = onSelect
        bottomSheet.show(supportFragmentManager, "SettingsBottomSheet")
    }

    private fun fetchResolutions() {
        cameraHelper.onResolutionsAvailable = { sizes ->
            runOnUiThread {
                availableResolutions = sizes
                    .filter { (it.width * it.height) >= 11_500_000 }
                    .map { "${Math.round(it.width * it.height / 1_000_000f)}mp" }
                    .distinct()
                    .sortedByDescending { it.replace("mp", "").toInt() }
                
                if (settings.photoResolution.isEmpty() && availableResolutions.isNotEmpty()) {
                    settings.photoResolution = availableResolutions.first()
                }
                updateRowValue(binding.rowPhotoResolution.root, settings.photoResolution)
            }
        }
        cameraHelper.fetchCameras()
        cameraHelper.fetchResolutions()
    }
}
