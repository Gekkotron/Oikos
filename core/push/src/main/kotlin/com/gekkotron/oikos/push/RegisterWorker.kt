package com.gekkotron.oikos.push

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

class RegisterWorker(
    context: Context,
    params: WorkerParameters,
    private val registrar: PushRegistrar,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (registrar.register()) {
        PushRegistrar.RegisterOutcome.Done, PushRegistrar.RegisterOutcome.NotConfigured -> Result.success()
        is PushRegistrar.RegisterOutcome.Unavailable -> Result.failure()
        is PushRegistrar.RegisterOutcome.Retry -> Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "register"
    }
}

/** Unique work `register`, REPLACE, network required, exponential backoff from 30 s (spec §5.4). */
class WorkManagerRegistrationQueue(private val workManager: () -> WorkManager) : RegistrationQueue {
    override fun enqueue() {
        val request = OneTimeWorkRequestBuilder<RegisterWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager().enqueueUniqueWork(RegisterWorker.UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel() {
        workManager().cancelUniqueWork(RegisterWorker.UNIQUE_NAME)
    }

    override val active: Flow<Boolean> = flow {
        emitAll(workManager().getWorkInfosForUniqueWorkFlow(RegisterWorker.UNIQUE_NAME).map { infos -> infos.any { !it.state.isFinished } })
    }

    private companion object {
        const val BACKOFF_SECONDS = 30L
    }
}
