package com.videocall.mobile.chat

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.videocall.mobile.App
import com.videocall.mobile.MainActivity
import com.videocall.mobile.net.Group
import com.videocall.mobile.net.Message
import com.videocall.mobile.net.User
import com.videocall.mobile.session.SessionManager

// isMentioned reports whether content contains "@username" as a whole token
// (not just a substring of a longer name), case-insensitively. Mirrors
// web/src/store/chats.ts's isMentioned exactly, for the same notification
// framing ("X mentioned you" vs "X in Y") on both platforms.
fun isMentioned(content: String, username: String): Boolean {
    if (username.isBlank()) return false
    val re = Regex("(^|\\s)@${Regex.escape(username)}\\b", RegexOption.IGNORE_CASE)
    return re.containsMatchIn(content)
}

/**
 * Posts the "Messages" channel notification for a message that arrived
 * while its conversation wasn't the one on screen — mirrors
 * web/src/store/chats.ts's `notify(...)` call in its message:new handler
 * (same DND check, same "mentioned you" vs "in <group>" title framing).
 *
 * CHANNEL_MESSAGES was declared in App.kt from the start but nothing ever
 * posted to it — an app that's caught up on calls but silent on chat isn't
 * actually reliable, so this was as much a correctness gap as the call
 * notification one.
 */
object ChatNotifier {
    fun notifyNewMessage(context: Context, convo: Convo, msg: Message, me: User, groups: List<Group>) {
        if (SessionManager.myStatus.value == "dnd") return // DND still tracks unread counts, just no popup
        val from = msg.sender?.display_name ?: "New message"
        val group = if (convo is Convo.GroupChat) groups.firstOrNull { it.id == convo.groupId } else null
        // Encrypted content can't be scanned for a mention without decrypting
        // first (async) — skip the "mentioned you" framing for those rather
        // than block the notification on it (same tradeoff as web).
        val mentioned = !msg.is_encrypted && isMentioned(msg.content, me.username)
        val title = when {
            mentioned && group != null -> "$from mentioned you in ${group.name}"
            mentioned -> "$from mentioned you"
            group != null -> "$from in ${group.name}"
            else -> from
        }
        val body = when {
            msg.is_encrypted -> "Encrypted message"
            msg.file != null && msg.content.isBlank() -> "Attachment: ${msg.file.name}"
            else -> msg.content
        }

        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, App.CHANNEL_MESSAGES)
            .setSmallIcon(com.videocall.mobile.R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        // One notification per conversation (new messages update it in place)
        // rather than one per message, same as the ID scheme other apps use.
        runCatching { NotificationManagerCompat.from(context).notify(convo.key.hashCode(), notification) }
    }
}
