package com.videocall.mobile.session

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.videocall.mobile.App
import com.videocall.mobile.MainActivity

/**
 * Keeps the process alive (and the realtime WebSocket connected) while the
 * app is logged in but not on an active call. This is a LAN/VPN-only
 * intranet app with no push service (deliberately no FCM/third-party
 * dependency) — the only way to still receive an incoming call while the
 * app is backgrounded is for the process itself to stay alive, which on
 * modern Android means running as a foreground service. Without this,
 * Doze/App Standby (and aggressive OEM battery managers) eventually freeze
 * network access for a backgrounded app and incoming calls silently never
 * arrive.
 *
 * CallService (a separate foreground service) takes over showing the
 * incoming/ongoing-call notification once a call actually starts; this one
 * just needs to exist so the process doesn't get killed in the meantime.
 */
class ConnectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, App.CHANNEL_CONNECTION)
            .setSmallIcon(com.videocall.mobile.R.drawable.ic_launcher_foreground)
            .setContentTitle("Vision Call")
            .setContentText("Connected — ready to receive calls")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
    }

    companion object {
        const val NOTIF_ID = 43

        fun start(context: Context) {
            val intent = Intent(context, ConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
