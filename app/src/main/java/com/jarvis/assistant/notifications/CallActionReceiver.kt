package com.jarvis.assistant.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.jarvis.assistant.automation.phone.PhoneCallController

class CallActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ANSWER = "com.jarvis.assistant.ACTION_ANSWER_CALL"
        const val ACTION_REJECT = "com.jarvis.assistant.ACTION_REJECT_CALL"
        private const val TAG = "CallActionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val result = when (action) {
            ACTION_ANSWER -> PhoneCallController.answer(context)
            ACTION_REJECT -> PhoneCallController.end(context)
            else -> return
        }
        Log.i(TAG, "$action -> ${result.message}")
        IncomingCallManager.clearPending()
        IncomingCallManager.cancelNotification(context)
        Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
    }
}
