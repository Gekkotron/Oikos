package com.gekkotron.oikos.feature.widget.work

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.gekkotron.oikos.feature.widget.R
import com.gekkotron.oikos.feature.widget.WidgetRefresher
import com.gekkotron.oikos.feature.widget.model.WidgetCommand

class WidgetRefreshWorker(context: Context, params: WorkerParameters, private val refresher: WidgetRefresher) : CoroutineWorker(context, params) {
    /** Always success: an unreachable house is shown in the footer, and the next period retries (no retry storm). */
    override suspend fun doWork(): Result {
        refresher.refresh()
        return Result.success()
    }
}

class WidgetCommandWorker(context: Context, params: WorkerParameters, private val refresher: WidgetRefresher) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val command = inputData.getString(KEY_COMMAND)?.let(WidgetCommand::decode) ?: return Result.failure()
        val token = inputData.getString(KEY_CODE_TOKEN)
        if (token == null) {
            refresher.send(id, command, code = null)
            return Result.success()
        }
        // Never send a coded command without its code: after a process death the vault is empty.
        val code = CodeVault.take(token)
        if (code == null) refresher.fail(id) else refresher.send(id, command, code)
        return Result.success()
    }

    /** Android 9 to 11 run expedited work as a foreground service and need something to show. */
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_widget_switch)
            .setContentTitle(applicationContext.getString(R.string.widget_sending))
            .setOngoing(true)
            .build(),
    )

    companion object {
        const val KEY_ID = "id"
        const val KEY_COMMAND = "command"
        const val KEY_CODE_TOKEN = "code_token"
        private const val NOTIFICATION_ID = 0x0D1C

        /** `AlertChannel.SYSTEM.id` (`:core:alerts`), created at app start; a literal keeps this module off `:core:alerts`. */
        const val CHANNEL_ID = "system"
    }
}

/** Builds the widget workers; the app chains it after the push factory in a `DelegatingWorkerFactory`. */
class WidgetWorkerFactory(private val refresher: () -> WidgetRefresher) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        when (workerClassName) {
            WidgetRefreshWorker::class.java.name -> WidgetRefreshWorker(appContext, workerParameters, refresher())
            WidgetCommandWorker::class.java.name -> WidgetCommandWorker(appContext, workerParameters, refresher())
            else -> null
        }
}
