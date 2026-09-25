package com.videocall.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import com.videocall.mobile.call.Ringer
import com.videocall.mobile.session.SessionManager

class App : Application(), coil.ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        com.videocall.mobile.ui.theme.ThemeState.load(this)
        SessionManager.init(this) // binds + registers CallRepository if a server URL is already saved
        createNotificationChannels()
    }

    /**
     * Avatars and image attachments are behind the session cookie. Coil's own
     * HTTP client doesn't have it, so every image used to 401 — route Coil
     * through the app's authenticated OkHttp client (looked up per request,
     * since switching servers rebinds it).
     */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .callFactory { okhttp3.Call.Factory { request -> SessionManager.api.client.newCall(request) } }
        .crossfade(true)
        .build()

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        val ringAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming and ongoing calls"
                // The channel sound only fires once on post; the actual ring loop
                // for as long as the call keeps ringing is handled separately by
                // Ringer (this app has no push service to wake a killed process,
                // so the loud, unmistakable ring while the process IS alive matters
                // more here than in an app that can rely on FCM).
                setSound(RingtoneManager.getActualDefaultRingtoneUri(this@App, RingtoneManager.TYPE_RINGTONE), ringAttrs)
                enableVibration(true)
                vibrationPattern = Ringer.VIBRATE_PATTERN
                setBypassDnd(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALL_ONGOING, "Ongoing call", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while you're on a call"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "New chat messages"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTION, "Background connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps you reachable for calls while the app is in the background"
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CHANNEL_CALLS = "calls"
        const val CHANNEL_CALL_ONGOING = "call_ongoing"
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_CONNECTION = "connection"
    }
}
