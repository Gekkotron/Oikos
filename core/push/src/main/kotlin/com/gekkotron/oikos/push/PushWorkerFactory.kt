package com.gekkotron.oikos.push

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

/** Builds the push workers with their dependencies; the app installs it as WorkManager's factory (Task 9). */
class PushWorkerFactory(
    private val registrar: PushRegistrar,
    private val publisher: TopicPublisher,
    private val targets: PushTargets,
    private val failures: ReplyFailureNotifier,
) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        when (workerClassName) {
            RegisterWorker::class.java.name -> RegisterWorker(appContext, workerParameters, registrar)
            ReplyWorker::class.java.name -> ReplyWorker(appContext, workerParameters, publisher, targets, failures)
            else -> null
        }
}
