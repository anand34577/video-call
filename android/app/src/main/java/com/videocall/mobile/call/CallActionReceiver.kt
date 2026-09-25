package com.videocall.mobile.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the Answer/Decline/Hang up actions from the call notification. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CallService.ACTION_ANSWER -> {
                val isRoomInvite = CallRepository.state.value.roomInvite != null
                val launch = Intent(context, CallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra(CallActivity.EXTRA_AUTO_ANSWER, !isRoomInvite)
                    putExtra(CallActivity.EXTRA_AUTO_JOIN_ROOM, isRoomInvite)
                }
                context.startActivity(launch)
            }
            CallService.ACTION_DECLINE -> {
                if (CallRepository.state.value.roomInvite != null) CallRepository.declineRoomInvite()
                else CallRepository.declineIncoming()
            }
            CallService.ACTION_HANGUP -> CallRepository.hangup()
        }
    }
}
