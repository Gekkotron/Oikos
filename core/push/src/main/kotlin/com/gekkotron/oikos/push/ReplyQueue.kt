package com.gekkotron.oikos.push

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class ReplyState { PENDING, SENT, FAILED }

/** Answers to notification and sheet action buttons (push alerts spec §5.5). */
interface ReplyQueue {
    /**
     * A second call for the same alert and action while one is pending does nothing (KEEP). Returns the id of the
     * request it enqueued; a duplicate's id never runs, so its [state] stays `PENDING` while the first one is reported.
     */
    fun enqueue(alertId: String, actionId: String, label: String): UUID

    /** One reply, for the sheet's "Sent" / "Couldn't send" snackbar. */
    fun state(id: UUID): Flow<ReplyState>

    /** Drops every pending reply ("Set up again"). */
    fun cancelAll()
}

internal fun uniqueName(alertId: String, actionId: String) = "reply-$alertId-$actionId"

/** [ts] is the tap time: it travels in the input data, so every retry publishes the same answer. */
internal fun replyRequest(alertId: String, actionId: String, label: String, ts: Long): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<ReplyWorker>()
        .setInputData(workDataOf(ReplyWorker.KEY_ALERT to alertId, ReplyWorker.KEY_ACTION to actionId, ReplyWorker.KEY_LABEL to label, ReplyWorker.KEY_TS to ts))
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .addTag(ReplyWorker.TAG)
        .build()

private const val BACKOFF_SECONDS = 10L

internal fun WorkInfo.State?.toReplyState(): ReplyState = when (this) {
    WorkInfo.State.SUCCEEDED -> ReplyState.SENT
    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> ReplyState.FAILED
    else -> ReplyState.PENDING
}

class WorkManagerReplyQueue(
    private val workManager: () -> WorkManager,
    private val clock: () -> Instant = Instant::now,
) : ReplyQueue {
    override fun enqueue(alertId: String, actionId: String, label: String): UUID {
        val request = replyRequest(alertId, actionId, label, clock().epochSecond)
        workManager().enqueueUniqueWork(uniqueName(alertId, actionId), ExistingWorkPolicy.KEEP, request)
        return request.id
    }

    override fun state(id: UUID): Flow<ReplyState> = flow {
        emitAll(workManager().getWorkInfoByIdFlow(id).map { it?.state.toReplyState() })
    }

    override fun cancelAll() {
        workManager().cancelAllWorkByTag(ReplyWorker.TAG)
    }
}
