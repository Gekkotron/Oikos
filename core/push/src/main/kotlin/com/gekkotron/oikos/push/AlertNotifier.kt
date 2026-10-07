package com.gekkotron.oikos.push

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.alerts.AlertAction
import com.gekkotron.oikos.alerts.media.AlertImageCache
import com.gekkotron.oikos.alerts.media.MediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The notification's picture, already decoded; null means text only. */
fun interface NotificationImages {
    suspend fun load(url: String): Bitmap?
}

/** Fetches through [MediaSource], keeps the bytes in the cache Coil reads, and decodes a bitmap small enough for a notification. */
class FetchedNotificationImages(
    private val source: MediaSource,
    private val cache: AlertImageCache,
    private val targetPx: Int = 1024,
) : NotificationImages {
    override suspend fun load(url: String): Bitmap? {
        val media = source.bytes(url, MAX_BYTES).getOrNull() ?: return null
        cache.put(url, media.bytes)
        return decodeSampled(media.bytes, targetPx)
    }

    private companion object {
        const val MAX_BYTES = 5L * 1024 * 1024
    }
}

internal fun sampleSizeFor(width: Int, height: Int, targetPx: Int): Int {
    var sample = 1
    while (width / (sample * 2) >= targetPx && height / (sample * 2) >= targetPx) sample *= 2
    return sample
}

internal fun decodeSampled(bytes: ByteArray, targetPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx) }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

/** Posts alerts as notifications (push alerts spec §5.2). */
class AlertNotifier(
    private val context: Context,
    private val images: NotificationImages,
    private val imageBudget: Duration = 8.seconds,
    private val canPost: () -> Boolean = { NotificationManagerCompat.from(context).areNotificationsEnabled() },
    private val show: (tag: String, notification: Notification) -> Unit = { tag, notification -> defaultShow(context, tag, notification) },
) {
    private val lock = Any()
    private val counter = AtomicLong()
    private val latest = HashMap<String, Long>()

    /**
     * True when the notification was posted. False when notifications are off, or when a newer post or a cancel for
     * this id arrived while the picture was loading: the last call for an id wins.
     */
    suspend fun post(alert: Alert): Boolean {
        if (!canPost()) return false
        val ticket = synchronized(lock) { counter.incrementAndGet().also { latest[alert.id] = it } }
        val picture = alert.imageUrl?.let { loadPicture(it) }
        synchronized(lock) {
            if (latest[alert.id] != ticket) return false
            show(alert.id, build(alert, picture))
        }
        return true
    }

    /** Dismisses the notification and voids any post still waiting for its picture. */
    fun cancel(id: String) = synchronized(lock) {
        latest[id] = counter.incrementAndGet()
        NotificationManagerCompat.from(context).cancel(id, NotificationIds.ALERT)
    }

    /** One budget for the whole load (LAN attempt, gateway fallback, decode); any failure means no picture. */
    private suspend fun loadPicture(url: String): Bitmap? = try {
        withTimeoutOrNull(imageBudget) { images.load(url) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    internal fun build(alert: Alert, picture: Bitmap?): Notification {
        val builder = NotificationCompat.Builder(context, alert.channel.id)
            .setSmallIcon(R.drawable.ic_stat_oikos)
            .setColor(AlertChannels.accent(alert.channel))
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setWhen(alert.sentAt.toEpochMilli())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setSilent(alert.silent)
            .setContentIntent(openIntent(alert.id))
        if (picture != null) {
            builder.setLargeIcon(picture)
                .setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).bigLargeIcon(null as Bitmap?).setSummaryText(alert.body))
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
        }
        alert.shownActions.forEach { builder.addAction(0, it.label, replyIntent(alert.id, it)) }
        return builder.build()
    }

    internal fun openIntent(alertId: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("oikos").authority("alert").appendPath(alertId).build())
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * Android matches PendingIntents on action, data, type, class and categories, never on extras. Every (alert, action)
     * pair therefore gets its own `data` URI; without it all "Disarm" buttons would be one PendingIntent.
     */
    internal fun replyIntent(alertId: String, action: AlertAction): PendingIntent {
        val intent = Intent(context, ReplyReceiver::class.java)
            .setAction(ReplyReceiver.ACTION_REPLY)
            .setData(Uri.Builder().scheme("oikos").authority("reply").appendPath(alertId).appendPath(action.id).build())
            .putExtra(ReplyReceiver.EXTRA_ALERT_ID, alertId)
            .putExtra(ReplyReceiver.EXTRA_ACTION_ID, action.id)
            .putExtra(ReplyReceiver.EXTRA_LABEL, action.label)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private companion object {
        @SuppressLint("MissingPermission") // post() checks areNotificationsEnabled() first
        fun defaultShow(context: Context, tag: String, notification: Notification) {
            NotificationManagerCompat.from(context).notify(tag, NotificationIds.ALERT, notification)
        }
    }
}
