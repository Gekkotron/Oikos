package com.gekkotron.oikos.push

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.alerts.PushTopics
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Publishes `{"id","action","install_id","ts"}` to `oikos-push/responses`, QoS 1, not retained (spec §5.5). */
class ReplyWorker(
    context: Context,
    params: WorkerParameters,
    private val publisher: TopicPublisher,
    private val targets: PushTargets,
    private val failures: ReplyFailureNotifier,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val alertId = inputData.getString(KEY_ALERT) ?: return Result.failure()
        val actionId = inputData.getString(KEY_ACTION) ?: return Result.failure()
        val label = inputData.getString(KEY_LABEL) ?: actionId
        val ts = inputData.getLong(KEY_TS, 0L)
        val installId = (targets.current() as? PushTarget.Ready)?.installId ?: return Result.success()
        val payload = buildJsonObject {
            put("id", alertId)
            put("action", actionId)
            put("install_id", installId)
            put("ts", ts)
        }
        // A publisher may rarely throw; that is a failed attempt like any other, never an escaped exception.
        val error = try {
            publisher.publish(PushTopics.RESPONSES, payload.toString().encodeToByteArray(), retain = false).exceptionOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
        return when {
            error == null || error is NotConfigured -> Result.success()
            runAttemptCount + 1 < MAX_ATTEMPTS -> Result.retry()
            else -> {
                failures.couldNotSend(alertId, label)
                Result.failure()
            }
        }
    }

    /** Android 9 to 11 run expedited work as a foreground service and need something to show. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val label = inputData.getString(KEY_LABEL).orEmpty()
        val notification = NotificationCompat.Builder(applicationContext, AlertChannel.SYSTEM.id)
            .setSmallIcon(R.drawable.ic_stat_oikos)
            .setContentTitle(applicationContext.getString(R.string.push_sending, label))
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NotificationIds.REPLY_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NotificationIds.REPLY_PROGRESS, notification)
        }
    }

    companion object {
        const val TAG = "reply"
        const val KEY_ALERT = "alert"
        const val KEY_ACTION = "action"
        const val KEY_LABEL = "label"
        const val KEY_TS = "ts"

        /** Five tries in total: the first four failures retry, the fifth gives up. */
        const val MAX_ATTEMPTS = 5
    }
}
