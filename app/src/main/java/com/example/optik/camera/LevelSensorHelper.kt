package com.example.optik.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2

class LevelSensorHelper(context: Context, private val onAnglesUpdated: (pitch: Float, roll: Float) -> Unit) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

    fun start() {
        gravitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_GRAVITY) {
                val x = it.values[0]
                val y = it.values[1]
                val z = it.values[2]

                // Calculate Roll (left/right tilt)
                val rollRaw = Math.toDegrees(atan2(-x.toDouble(), y.toDouble())).toFloat()
                
                // Calculate Pitch (forward/backward tilt)
                val pitchRaw = Math.toDegrees(atan2(-z.toDouble(), Math.sqrt((x*x + y*y).toDouble()))).toFloat()
                
                // Snap roll to nearest 90 degrees so it works in both portrait and landscape
                var rollSnapped = (rollRaw + 45f) / 90f
                if (rollSnapped < 0) rollSnapped -= 1f // Fix flooring for negative numbers
                val nearest90 = rollSnapped.toInt() * 90f
                val roll = rollRaw - nearest90
                
                // Pitch is 0 when the device is perfectly upright
                val pitch = pitchRaw

                onAnglesUpdated(pitch, roll)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
