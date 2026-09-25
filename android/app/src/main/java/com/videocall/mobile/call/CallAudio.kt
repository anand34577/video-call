package com.videocall.mobile.call

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Call audio routing. Without MODE_IN_COMMUNICATION the platform echo
 * canceller and voice routing aren't engaged and call audio plays like media;
 * video calls default to the loudspeaker, voice calls to the earpiece, and the
 * in-call speaker button flips between them.
 */
object CallAudio {
    private var active = false
    private var previousMode = AudioManager.MODE_NORMAL

    private fun am(ctx: Context) = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun start(ctx: Context, speaker: Boolean) {
        val am = am(ctx)
        if (!active) {
            previousMode = am.mode
            active = true
        }
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeaker(ctx, speaker)
    }

    fun setSpeaker(ctx: Context, on: Boolean) {
        val am = am(ctx)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val wanted = if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            // A connected headset or Bluetooth device wins over the earpiece.
            val external = am.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            val device = if (!on && external != null) external else am.availableCommunicationDevices.firstOrNull { it.type == wanted }
            if (device != null) am.setCommunicationDevice(device) else am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        }
    }

    fun stop(ctx: Context) {
        if (!active) return
        active = false
        val am = am(ctx)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
        else @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = false }
        am.mode = previousMode
    }
}
