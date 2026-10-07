package com.gekkotron.oikos.push

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gekkotron.oikos.alerts.AlertChannel

/** "Couldn't send *label*" on the `system` channel after the fifth failed attempt (spec §5.5). */
class ReplyFailureNotifier(
    private val context: Context,
    private val canPost: () -> Boolean = { NotificationManagerCompat.from(context).areNotificationsEnabled() },
) {
    @SuppressLint("MissingPermission") // canPost() is the permission check
    fun couldNotSend(alertId: String, label: String) {
        if (!canPost()) return
        val notification = NotificationCompat.Builder(context, AlertChannel.SYSTEM.id)
            .setSmallIcon(R.drawable.ic_stat_oikos)
            .setColor(AlertChannels.accent(AlertChannel.SYSTEM))
            .setContentTitle(context.getString(R.string.push_reply_failed, label))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify("reply-failed:$alertId", NotificationIds.REPLY_FAILED, notification)
    }
}
