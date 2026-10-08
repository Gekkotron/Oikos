package com.gekkotron.oikos.feature.widget.work

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.gekkotron.oikos.feature.widget.model.WidgetCommand
import java.util.concurrent.TimeUnit

/** When the widget refreshes and sends (widgets spec §3.2). */
interface WidgetScheduler {
    fun ensurePeriodic()

    fun cancelPeriodic()

    fun refreshNow()

    fun send(id: String, command: WidgetCommand, code: String?)
}

class WorkManagerWidgetScheduler(private val workManager: () -> WorkManager) : WidgetScheduler {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** KEEP: placing or reconfiguring a widget must not restart the 15-minute period. */
    override fun ensurePeriodic() {
        val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES).setConstraints(network).build()
        workManager().enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancelPeriodic() {
        workManager().cancelUniqueWork(PERIODIC)
    }

    override fun refreshNow() {
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>().setConstraints(network).build()
        workManager().enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }

    /** Expedited. The code (if any) stays in [CodeVault]; the work input, which WorkManager keeps on disk, holds a token. */
    override fun send(id: String, command: WidgetCommand, code: String?) {
        val request = OneTimeWorkRequestBuilder<WidgetCommandWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG_COMMAND)
            .setInputData(commandInput(id, command, code))
            .build()
        workManager().enqueue(request)
    }

    companion object {
        const val PERIODIC = "oikos-widget-refresh"
        const val NOW = "oikos-widget-refresh-now"
        const val TAG_COMMAND = "oikos-widget-command"

        fun commandInput(id: String, command: WidgetCommand, code: String?): Data = workDataOf(
            WidgetCommandWorker.KEY_ID to id,
            WidgetCommandWorker.KEY_COMMAND to command.encode(),
            WidgetCommandWorker.KEY_CODE_TOKEN to code?.let(CodeVault::put),
        )
    }
}
