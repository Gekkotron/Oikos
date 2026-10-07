package com.gekkotron.oikos.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

/** A notification action button: dismiss the notification and queue the answer (push alerts spec §5.5). */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alertId = intent.getStringExtra(EXTRA_ALERT_ID) ?: return
        val actionId = intent.getStringExtra(EXTRA_ACTION_ID) ?: return
        val label = intent.getStringExtra(EXTRA_LABEL) ?: actionId
        NotificationManagerCompat.from(context).cancel(alertId, NotificationIds.ALERT)
        (context.applicationContext as PushComponentProvider).pushComponent().replies.enqueue(alertId, actionId, label)
    }

    companion object {
        const val ACTION_REPLY = "com.gekkotron.oikos.push.REPLY"
        const val EXTRA_ALERT_ID = "alert_id"
        const val EXTRA_ACTION_ID = "action_id"
        const val EXTRA_LABEL = "label"
    }
}
