package com.videocall.mobile.call

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.videocall.mobile.App
import com.videocall.mobile.R

/**
 * Shows a ringing call while the app is in the background, as a plain
 * notification with a full-screen intent (the lock-screen call screen).
 *
 * This deliberately does not start a foreground service. Android 12+ refuses
 * to start one from the background, and Android 14 throws when a background
 * app starts one that uses the microphone or camera, which used to crash the
 * app on every call that arrived while it wasn't open. The process is kept
 * alive by ConnectionService; CallService starts once the call is answered.
 *
 * Its channel has no sound: Ringer plays the ringtone, so it only rings once.
 */
object IncomingCallNotifier {
    // Must not clash with ConnectionService (43) or CallService (42): reusing
    // a foreground service's id replaced its notification, and Android never
    // lets an app remove that, so the ringing call stayed on screen forever.
    private const val NOTIF_ID = 44
    private const val MISSED_ID = 45

    fun show(context: Context, state: CallUiState) {
        val incoming = state.incoming
        val invite = state.roomInvite
        if (incoming == null && invite == null) return

        val who = incoming?.from?.display_name ?: invite!!.groupName
        val text = when {
            incoming != null -> if (incoming.video) "Incoming video call" else "Incoming voice call"
            else -> "${invite!!.from.display_name} started a group call"
        }
        val caller = Person.Builder().setName(who).setImportant(true).build()

        val open = activityIntent(context, 0, autoAnswer = false, autoJoin = false)
        val answer = activityIntent(context, 1, autoAnswer = incoming != null, autoJoin = invite != null)
        val decline = PendingIntent.getBroadcast(
            context, 2,
            Intent(context, CallActionReceiver::class.java).setAction(CallService.ACTION_DECLINE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, App.CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(who)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addPerson(caller)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer))
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification) }
    }

    fun cancel(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
    }

    /** "Missed call from ..." after a call that rang out or was cancelled. */
    fun showMissed(context: Context, caller: com.videocall.mobile.net.UserBrief, video: Boolean) {
        val open = PendingIntent.getActivity(
            context, 3,
            Intent(context, com.videocall.mobile.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, App.CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Missed ${if (video) "video" else "voice"} call")
            .setContentText(caller.display_name)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(MISSED_ID, notification) }
    }

    // Answer opens the call screen directly: Android 12+ blocks starting an
    // activity from a broadcast receiver behind a notification action.
    private fun activityIntent(context: Context, requestCode: Int, autoAnswer: Boolean, autoJoin: Boolean): PendingIntent {
        val intent = Intent(context, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(CallActivity.EXTRA_AUTO_ANSWER, autoAnswer)
            putExtra(CallActivity.EXTRA_AUTO_JOIN_ROOM, autoJoin)
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
