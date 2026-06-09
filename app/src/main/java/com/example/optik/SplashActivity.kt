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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No layout needed, transparent background or just a solid color is fine
        
        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        // We only STRICTLY block on Camera permission for starting the app.
        // Location and Media can be optional, but we request all.
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
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
            // Check if Camera is granted
            val cameraIndex = permissions.indexOf(Manifest.permission.CAMERA)
            if (cameraIndex != -1 && grantResults[cameraIndex] == PackageManager.PERMISSION_GRANTED) {
                proceedToApp()
            } else {
                // Camera denied, can't use the app. Just finish.
                finish()
            }
        }
    }

    private fun proceedToApp() {
        val settings = SettingsManager.getInstance(this)
        
        val isManual = if (settings.startupMode == 0) {
            false // Luôn dùng chế độ Basic
        } else {
            settings.lastUsedMode == 1 // CĐ chụp trước
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
