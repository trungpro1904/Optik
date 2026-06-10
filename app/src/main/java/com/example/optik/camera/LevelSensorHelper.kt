package com.example.optik.camera
//thước
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

                // Calculate Roll (thước ngang)
                val rollRaw = Math.toDegrees(atan2(-x.toDouble(), y.toDouble())).toFloat()
                
                // Calculate Pitch (thước dọc)
                val pitchRaw = Math.toDegrees(atan2(-z.toDouble(), Math.sqrt((x*x + y*y).toDouble()))).toFloat()
                
                // snap thước ngang về gốc 90 độ để quay ngang dọc vẫn hoạt động đúng
                var rollSnapped = (rollRaw + 45f) / 90f
                if (rollSnapped < 0) rollSnapped -= 1f // Fix flooring for negative numbers
                val nearest90 = rollSnapped.toInt() * 90f
                val roll = rollRaw - nearest90
                
                // thước dọc bằng 0 khi thiết bị ở phương thẳng đứng
                val pitch = pitchRaw

                onAnglesUpdated(pitch, roll)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
