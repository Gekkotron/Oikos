package com.gekkotron.oikos.feature.widget.ui

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import com.gekkotron.oikos.feature.widget.TileAction
import com.gekkotron.oikos.feature.widget.WidgetComponent
import com.gekkotron.oikos.feature.widget.WidgetComponentProvider
import com.gekkotron.oikos.feature.widget.code.WidgetCodeActivity
import com.gekkotron.oikos.feature.widget.config.WidgetConfigActivity
import com.gekkotron.oikos.feature.widget.model.WidgetCommand
import com.gekkotron.oikos.feature.widget.model.WidgetConfig
import com.gekkotron.oikos.feature.widget.model.WidgetSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The links the widget opens in the app; `:app` parses them (widgets spec §2.3). */
object AppLinks {
    const val HISTORY = "oikos://history/"
    const val SETTINGS = "oikos://settings"
}

private val Context.widgetComponent: WidgetComponent get() = (applicationContext as WidgetComponentProvider).widgetComponent()

/** "Oikos devices": draws from the config and snapshot stores only and never connects (widgets spec §3.2). */
class OikosWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(110.dp, 48.dp), DpSize(180.dp, 110.dp), DpSize(250.dp, 110.dp), DpSize(180.dp, 250.dp), DpSize(250.dp, 250.dp)),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val component = context.widgetComponent
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val data = combine(component.configs.configs, component.snapshots.snapshot) { configs, snapshot -> configs[appWidgetId] to snapshot }
        provideContent {
            val current by data.collectAsState(initial = null to WidgetSnapshot())
            GlanceTheme {
                WidgetContent(uiState(current.first, current.second, System.currentTimeMillis()), Actions(context, appWidgetId))
            }
        }
    }

    private fun uiState(config: WidgetConfig?, snapshot: WidgetSnapshot, now: Long) = WidgetUiState(
        tiles = config?.uniqueIds.orEmpty().map { snapshot.tiles[it] },
        armMode = config?.armMode,
        link = snapshot.link,
        lastFresh = snapshot.lastFresh,
        now = now,
        configured = !config?.uniqueIds.isNullOrEmpty(),
    )

    private class Actions(private val context: Context, private val appWidgetId: Int) : WidgetActions {
        override fun tap(action: TileAction): Action = when (action) {
            is TileAction.Send -> actionRunCallback<SendCallback>(actionParametersOf(SendCallback.ID to action.id, SendCallback.COMMAND to action.command.encode()))
            is TileAction.AskCode -> actionStartActivity(WidgetCodeActivity.intent(context, action.id, action.command, action.format))
            is TileAction.OpenHistory -> actionStartActivity(view(AppLinks.HISTORY + Uri.encode(action.id)))
            TileAction.OpenHome -> openApp()
        }

        override fun refresh(): Action = actionRunCallback<RefreshCallback>()

        override fun reconfigure(): Action = actionStartActivity(
            Intent(context, WidgetConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        override fun openApp(): Action = actionStartActivity(
            context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName),
        )

        override fun openSettings(): Action = actionStartActivity(view(AppLinks.SETTINGS))

        private fun view(uri: String) = Intent(Intent.ACTION_VIEW, uri.toUri()).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** A tap that sends a command: queued as expedited work so it survives the broadcast's short life. */
class SendCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[ID] ?: return
        val command = parameters[COMMAND]?.let(WidgetCommand::decode) ?: return
        context.widgetComponent.scheduler.send(id, command, code = null)
    }

    companion object {
        val ID = ActionParameters.Key<String>("id")
        val COMMAND = ActionParameters.Key<String>("command")
    }
}

class RefreshCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        context.widgetComponent.scheduler.refreshNow()
    }
}

class OikosWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = OikosWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val configs = context.widgetComponent.configs
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                configs.remove(appWidgetIds)
            } finally {
                pending.finish()
            }
        }
    }

    /** The last widget is gone: stop the periodic refresh. */
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        context.widgetComponent.scheduler.cancelPeriodic()
    }
}

/** Redraws every Oikos widget (the app's `WidgetUpdater`). */
suspend fun updateAllOikosWidgets(context: Context) = OikosWidget().updateAll(context)
