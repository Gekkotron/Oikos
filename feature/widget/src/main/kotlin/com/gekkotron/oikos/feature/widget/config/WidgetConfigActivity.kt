package com.gekkotron.oikos.feature.widget.config

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.widget.WidgetComponentProvider
import com.gekkotron.oikos.feature.widget.model.WidgetConfig
import com.gekkotron.oikos.feature.widget.ui.tilesThatFit
import kotlinx.coroutines.launch

/** Opened by the launcher when a widget is placed or reconfigured (widgets spec §2.1). */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // A back press cancels the placement unless we say otherwise.
        setResult(RESULT_CANCELED, result(appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val component = (application as WidgetComponentProvider).widgetComponent()
        val options = AppWidgetManager.getInstance(this).getAppWidgetOptions(appWidgetId)
        val fit = tilesThatFit(
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, DEFAULT_SIZE).dp,
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, DEFAULT_SIZE).dp,
        ).coerceAtMost(WidgetConfig.MAX_ENTITIES)
        val vm = ViewModelProvider(
            this,
            viewModelFactory { initializer { ConfigViewModel(appWidgetId, component.entities, component.configs, component.scheduler, fit) } },
        )[ConfigViewModel::class.java]
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            OikosTheme {
                ConfigScreen(
                    state,
                    onSearch = vm::search,
                    onToggle = vm::toggle,
                    onMove = vm::move,
                    onArmMode = vm::setArmMode,
                    onDone = {
                        lifecycleScope.launch {
                            if (vm.save()) {
                                setResult(RESULT_OK, result(appWidgetId))
                                finish()
                            }
                        }
                    },
                    onOpenApp = {
                        packageManager.getLaunchIntentForPackage(packageName)?.let(::startActivity)
                        finish()
                    },
                )
            }
        }
    }

    private fun result(appWidgetId: Int) = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

    private companion object {
        /** A 2×2 widget, when the launcher has not reported a size yet. */
        const val DEFAULT_SIZE = 180
    }
}
