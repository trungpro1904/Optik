package com.example.optik

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.optik.settings.SettingsManager

class SplashActivity : AppCompatActivity() {

    private val PERMISSIONS_REQUEST_CODE = 100

    private val requiredPermissions = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= 34) { // Android 14+
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else if (Build.VERSION.SDK_INT >= 33) { // Android 13
            add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val permissionsToRequest = requiredPermissions.filter {
            var isGranted = ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            
            // Xử lý quyền trên Android 14 (Nếu cho phép 1 phần hoặc toàn bộ thì đều tính là đã cấp)
            if (Build.VERSION.SDK_INT >= 34) {
                if (it == Manifest.permission.READ_MEDIA_IMAGES) {
                    val partial = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
                    isGranted = isGranted || partial
                } else if (it == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) {
                    val full = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    isGranted = isGranted || full
                }
            }
            !isGranted
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest, PERMISSIONS_REQUEST_CODE)
        } else {
            proceedToApp()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            // Kiểm tra trạng thái cấp quyền hiện tại bằng ContextCompat thay vì chỉ số mảng
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                proceedToApp()
            } else {
                finish()
            }
        }
    }

    private fun proceedToApp() {
        val settings = SettingsManager.getInstance(this)
        
        val isManual = if (settings.startupMode == 0) {
            false
        } else {
            settings.lastUsedMode == 1
        }
        
        val intent = if (isManual) {
            Intent(this, ManualActivity::class.java)
        } else {
            Intent(this, BasicActivity::class.java)
        }
        
        startActivity(intent)
        finish()
    }
}
