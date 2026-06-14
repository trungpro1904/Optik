package com.example.optik.camera

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.example.optik.R
import com.example.optik.settings.SettingsManager

class SoundHelper(private val context: Context) {

    private val soundPool: SoundPool
    private var shutterSoundId: Int = 0
    private var recStartSoundId: Int = 0
    private var recStopSoundId: Int = 0
    private var beepSoundId: Int = 0
    
    private val settings = SettingsManager.getInstance(context)

    init {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(5)
            .setAudioAttributes(audioAttributes)
            .build()

        shutterSoundId = soundPool.load(context, R.raw.shutter_sound, 1)
        recStartSoundId = soundPool.load(context, R.raw.rec_start, 1)
        recStopSoundId = soundPool.load(context, R.raw.rec_stop, 1)
        beepSoundId = soundPool.load(context, R.raw.beep, 1)
    }

    fun playShutter() {
        if (settings.isShutterSoundEnabled) {
            soundPool.play(shutterSoundId, 1f, 1f, 0, 0, 1f)
        }
    }

    fun playRecStart() {
        soundPool.play(recStartSoundId, 1f, 1f, 0, 0, 1f)
    }

    fun playRecStop() {
        soundPool.play(recStopSoundId, 1f, 1f, 0, 0, 1f)
    }

    fun playBeep(rate: Float = 1f) {
        soundPool.play(beepSoundId, 1f, 1f, 0, 0, rate)
    }

    fun release() {
        soundPool.release()
    }
}
