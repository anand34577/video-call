package com.videocall.mobile

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import com.videocall.mobile.session.SessionManager

class App : Application(), coil.ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        com.videocall.mobile.ui.theme.ThemeState.load(this)
        SessionManager.init(this) // binds + registers CallRepository if a server URL is already saved
        createNotificationChannels()
        registerActivityLifecycleCallbacks(ForegroundTracker)
    }

    /** Counts visible activities, so calls know whether the app is on screen. */
    private object ForegroundTracker : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) { startedActivities++ }
        override fun onActivityStopped(activity: Activity) { startedActivities = (startedActivities - 1).coerceAtLeast(0) }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
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
        // The first release's "calls" channel played the ringtone itself while
        // Ringer played it too, so every call rang twice. A channel's sound
        // can't be changed after creation, so replace it with a silent one.
        nm.deleteNotificationChannel("calls")
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RINGING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rings for incoming calls"
                setSound(null, null)
                enableVibration(false) // Ringer vibrates while the call rings
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
        const val CHANNEL_RINGING = "calls_ringing"
        const val CHANNEL_CALL_ONGOING = "call_ongoing"
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_CONNECTION = "connection"

        @Volatile
        private var startedActivities = 0

        /** True while any of the app's screens is visible. */
        val isInForeground: Boolean get() = startedActivities > 0
    }
}
