package com.example.optik.settings

import android.hardware.camera2.params.RggbChannelVector
import kotlin.math.ln
import kotlin.math.pow

object WhitebalanceHelper {
    
    // A-B and G-M are typically from -9 to +9
    const val MAX_TINT = 9
    
    fun getStandardKelvinRange(): List<Int> {
        val list = mutableListOf<Int>()
        for (k in 2500..10000 step 100) {
            list.add(k)
        }
        return list
    }

    fun colorTemperatureToRggb(kelvin: Int, tintAB: Int, tintGM: Int): RggbChannelVector {
        val rgb = kelvinToRgb(kelvin)
        var r = rgb[0]
        var g = rgb[1]
        var b = rgb[2]

        // Base gains (normalize to Green) with typical Daylight sensor baseline multipliers
        // Sensors usually need ~2.0x Red and ~1.8x Blue gain at 5500K daylight to neutralize the native green bias.
        var rGain = if (r > 0) (g / r) * 2.0f else 2.0f
        var bGain = if (b > 0) (g / b) * 1.8f else 1.8f
        var gGain = 1f

        // Apply 45-degree rotation to A-B and G-M axes as requested by user
        val abRaw = tintAB / 9f
        val gmRaw = tintGM / 9f
        
        val cx = (abRaw + gmRaw) * 0.707f
        val cy = (-abRaw + gmRaw) * 0.707f

        // Apply A-B tint (Amber-Blue)
        val abFactor = cx * 9f * 0.05f 
        rGain += abFactor
        bGain -= abFactor

        // Apply G-M tint (Green-Magenta)
        val gmFactor = cy * 9f * 0.05f
        gGain += gmFactor

        // Ensure gains are strictly positive
        rGain = rGain.coerceAtLeast(0.1f)
        gGain = gGain.coerceAtLeast(0.1f)
        bGain = bGain.coerceAtLeast(0.1f)

        return RggbChannelVector(rGain, gGain, gGain, bGain)
    }

    private fun kelvinToRgb(kelvin: Int): FloatArray {
        val temp = kelvin / 100.0f
        var r = 0f
        var g = 0f
        var b = 0f

        if (temp <= 66f) {
            r = 255f
            g = (99.4708025861 * ln(temp.toDouble()) - 161.1195681661).toFloat()
            b = if (temp <= 19f) 0f else (138.5177312231 * ln((temp - 10).toDouble()) - 305.0447927307).toFloat()
        } else {
            r = (329.698727446 * (temp - 60).toDouble().pow(-0.1332047592)).toFloat()
            g = (288.1221695283 * (temp - 60).toDouble().pow(-0.0755148492)).toFloat()
            b = 255f
        }

        r = r.coerceIn(0f, 255f) / 255f
        g = g.coerceIn(0f, 255f) / 255f
        b = b.coerceIn(0f, 255f) / 255f
        
        // Failsafe if rgb is 0
        if (r == 0f && g == 0f && b == 0f) {
            r = 1f; g = 1f; b = 1f
        }
        
        return floatArrayOf(r, g, b)
    }
}
