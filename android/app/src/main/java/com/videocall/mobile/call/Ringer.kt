package com.videocall.mobile.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Loops the device's default ringtone + a vibration pattern while a call is
 * ringing. The notification channel's own sound (see App.kt) only fires once
 * per post, which isn't enough to actually get a user's attention the way a
 * real phone call does — this is the difference between "silently missed
 * it" and "heard it across the room". No push service on this build (LAN/VPN
 * intranet app, deliberately no third-party dependency), so this only helps
 * while the process is alive, but it's cheap and it matters.
 */
object Ringer {
    // 0ms wait, 800ms buzz, 800ms pause, repeating from index 0.
    val VIBRATE_PATTERN = longArrayOf(0, 800, 800)

    private var player: MediaPlayer? = null
    private var vibrating = false

    @Synchronized
    fun start(context: Context) {
        if (player != null) return // already ringing
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am == null || am.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            startSound(context)
        }
        startVibration(context)
    }

    private fun startSound(context: Context) {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE) ?: return
        runCatching {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(context, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            player = mp
        }
    }

    private fun startVibration(context: Context) {
        val vibrator = vibratorOf(context) ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(VIBRATE_PATTERN, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(VIBRATE_PATTERN, 0)
            }
            vibrating = true
        }
    }

    private fun vibratorOf(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    @Synchronized
    fun stop(context: Context) {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        if (vibrating) {
            runCatching { vibratorOf(context)?.cancel() }
            vibrating = false
        }
    }
}
