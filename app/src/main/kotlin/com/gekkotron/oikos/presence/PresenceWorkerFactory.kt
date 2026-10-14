package com.gekkotron.oikos.presence

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gekkotron.oikos.data.AppPreferences
import com.gekkotron.oikos.push.TopicPublisher
import java.util.concurrent.TimeUnit

class PresenceWorkerFactory(
    private val publisher: TopicPublisher,
    private val preferences: AppPreferences,
    private val fixSource: CurrentLocationSource,
) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        when (workerClassName) {
            PresenceWorker::class.java.name -> PresenceWorker(appContext, workerParameters, publisher, preferences)
            PresenceRefreshWorker::class.java.name -> PresenceRefreshWorker(appContext, workerParameters, publisher, preferences, fixSource)
            else -> null
        }
}

/** Enqueues one publish per transition, replacing an unfinished one for the same state (debounce). */
object PresenceWork {
    fun enqueue(context: Context, state: PresenceState) {
        val request = OneTimeWorkRequestBuilder<PresenceWorker>()
            .addTag(PresenceWorker.TAG)
            .setInputData(workDataOf(PresenceWorker.KEY_STATE to state.name))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}

/** Enqueues a refresh in response to a silent FCM `cmd=presence_refresh`. Network-constrained, expedited when possible. */
object PresenceRefreshWork {
    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<PresenceRefreshWorker>()
            .addTag(PresenceRefreshWorker.TAG)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
