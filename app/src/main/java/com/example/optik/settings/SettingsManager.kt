package com.example.optik.settings

import android.content.Context
import android.content.SharedPreferences

class SettingsManager private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("OptikSettings", Context.MODE_PRIVATE)

    companion object {
        @Volatile
        private var instance: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return instance ?: synchronized(this) {
                instance ?: SettingsManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // Startup Mode: 0 = Basic, 1 = Manual, 2 = Previous
    var startupMode: Int
        get() = prefs.getInt("startupMode", 0)
        set(value) = prefs.edit().putInt("startupMode", value).apply()

    // Last Used Mode: 0 = Basic, 1 = Manual
    var lastUsedMode: Int
        get() = prefs.getInt("lastUsedMode", 0)
        set(value) = prefs.edit().putInt("lastUsedMode", value).apply()

    // Default Photo Format: "JPEG", "RAW", "RAW+JPEG"
    var photoFormat: String
        get() = prefs.getString("photoFormat", "JPEG") ?: "JPEG"
        set(value) = prefs.edit().putString("photoFormat", value).apply()

    // Default Photo Resolution: max string "12mp", "48mp", etc. Will be populated dynamically if not set.
    var photoResolution: String
        get() = prefs.getString("photoResolution", "") ?: ""
        set(value) = prefs.edit().putString("photoResolution", value).apply()

    // Default Video Format: "4K", "HD", "720p"
    var videoFormat: String
        get() = prefs.getString("videoFormat", "HD") ?: "HD"
        set(value) = prefs.edit().putString("videoFormat", value).apply()

    // FPS: "120", "60", "30"
    var videoFps: String
        get() = prefs.getString("videoFps", "60") ?: "60"
        set(value) = prefs.edit().putString("videoFps", value).apply()

    // Aspect Ratio: "4:3", "16:9", "1:1"
    var aspectRatio: String
        get() = prefs.getString("aspectRatio", "4:3") ?: "4:3"
        set(value) = prefs.edit().putString("aspectRatio", value).apply()

    // Volume Key Action: "Zoom", "Chụp" (Capture)
    var volumeKeyAction: String
        get() = prefs.getString("volumeKeyAction", "Zoom") ?: "Zoom"
        set(value) = prefs.edit().putString("volumeKeyAction", value).apply()

    // Touch Action: "Dò tìm đối tượng", "Tiêu điểm và độ sáng"
    var touchAction: String
        get() = prefs.getString("touchAction", "Dò tìm đối tượng") ?: "Dò tìm đối tượng"
        set(value) = prefs.edit().putString("touchAction", value).apply()

    // RAW Compression: "Không nén", "Nén không mất mát", "Nén"
    var rawCompression: String
        get() = prefs.getString("rawCompression", "Không nén") ?: "Không nén"
        set(value) = prefs.edit().putString("rawCompression", value).apply()

    // Location Enabled
    var isLocationEnabled: Boolean
        get() = prefs.getBoolean("isLocationEnabled", false)
        set(value) = prefs.edit().putBoolean("isLocationEnabled", value).apply()

    // Grid Enabled
    var isGridEnabled: Boolean
        get() = prefs.getBoolean("isGridEnabled", false)
        set(value) = prefs.edit().putBoolean("isGridEnabled", value).apply()

    // Level (Thước) Enabled
    var isLevelEnabled: Boolean
        get() = prefs.getBoolean("isLevelEnabled", false)
        set(value) = prefs.edit().putBoolean("isLevelEnabled", value).apply()

    // Haptic Feedback Enabled
    var isHapticEnabled: Boolean
        get() = prefs.getBoolean("isHapticEnabled", true)
        set(value) = prefs.edit().putBoolean("isHapticEnabled", value).apply()
}
