package com.videocall.mobile.call

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import com.videocall.mobile.App
import com.videocall.mobile.MainActivity

/**
 * Foreground service whose only job is keeping the process (and the mic/
 * camera) alive while a call is ringing or active, and showing the
 * incoming-call / ongoing-call notification. All call logic lives in
 * CallRepository; this just mirrors its state into a notification.
 */
class CallService : Service() {
    private var job: Job? = null
    private var lastSignature: List<Any?>? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundWithType(baseType())
        job = CallRepository.state.onEach { state ->
            if (!state.isLive) {
                stopSelf()
            } else {
                // Re-post only when what the notification shows changes, not on
                // every track/network-quality update.
                val sig = listOf(state.status, state.incoming?.callId, state.roomInvite?.roomId, state.peer?.id, state.group?.id, state.startedAt)
                if (sig != lastSignature) {
                    lastSignature = sig
                    val nm = getSystemService(android.app.NotificationManager::class.java)
                    nm.notify(NOTIF_ID, buildNotification(state))
                }
                if (state.incoming != null || state.roomInvite != null) Ringer.start(this) else Ringer.stop(this)
            }
        }.launchIn(scope)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        job?.cancel()
        Ringer.stop(this)
        super.onDestroy()
    }

    /**
     * FOREGROUND_SERVICE_TYPE_CAMERA may only be declared when the CAMERA
     * runtime permission is actually granted, else startForeground() throws
     * SecurityException on Android 12+ — which crashed every audio-only call
     * for users who never separately granted camera access.
     */
    private fun baseType(): Int {
        val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (hasCamera) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        return type
    }

    private fun startForegroundWithType(type: Int) {
        val notification = buildNotification(CallRepository.state.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(state: CallUiState): Notification {
        val isRinging = state.incoming != null || state.roomInvite != null
        val title = when {
            state.incoming != null -> "Incoming call"
            state.roomInvite != null -> "Group call invite"
            state.status == CallStatus.OUTGOING -> "Calling…"
            state.status == CallStatus.CONNECTING -> "Connecting…"
            state.status == CallStatus.ACTIVE -> "Call in progress"
            else -> "Vision Call"
        }
        val who = state.incoming?.from?.display_name ?: state.roomInvite?.groupName ?: state.peer?.display_name ?: state.group?.name ?: "call"
        val fullScreenIntent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val fullScreenPending = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val caller = androidx.core.app.Person.Builder().setName(who).setImportant(true).build()
        // Ringing uses the high-importance channel (heads-up + ringtone); an
        // ongoing call uses a silent one, so status changes (connecting ->
        // active) never pop a heads-up over the call screen.
        val builder = NotificationCompat.Builder(this, if (isRinging) App.CHANNEL_CALLS else App.CHANNEL_CALL_ONGOING)
            .setSmallIcon(com.videocall.mobile.R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(who)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(if (isRinging) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(fullScreenPending)
            .addPerson(caller)

        if (isRinging) {
            builder.setFullScreenIntent(fullScreenPending, true)
            builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, actionPending(ACTION_DECLINE), actionPending(ACTION_ANSWER)))
        } else {
            if (state.status == CallStatus.ACTIVE && state.startedAt != null) {
                builder.setUsesChronometer(true)
                builder.setWhen(System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - state.startedAt))
            }
            builder.setStyle(NotificationCompat.CallStyle.forOngoingCall(caller, actionPending(ACTION_HANGUP)))
        }
        return builder.build()
    }

    private fun actionPending(action: String): PendingIntent {
        val intent = Intent(this, CallActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(this, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val NOTIF_ID = 42
        const val ACTION_ANSWER = "com.videocall.mobile.ANSWER"
        const val ACTION_DECLINE = "com.videocall.mobile.DECLINE"
        const val ACTION_HANGUP = "com.videocall.mobile.HANGUP"

        private var instance: CallService? = null

        fun ensureRunning(context: android.content.Context) {
            val intent = Intent(context, CallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        /**
         * Screen capture on API 34+ requires an already-running FGS of type
         * mediaProjection. Only add that type once the user has granted
         * projection consent from a foreground activity — declaring it
         * unconditionally at service start makes the OS apply mediaProjection's
         * stricter start-time checks even for ordinary mic/camera calls.
         */
        fun addMediaProjectionType() {
            instance?.let { it.startForegroundWithType(it.baseType() or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) }
        }

        fun removeMediaProjectionType() {
            instance?.let { it.startForegroundWithType(it.baseType()) }
        }
    }
}
