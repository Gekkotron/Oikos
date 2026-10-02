package com.gekkotron.oikos.feature.security.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.alarm.AlarmHero
import com.gekkotron.oikos.designsystem.components.LinkPill
import com.gekkotron.oikos.designsystem.components.OikosCard
import com.gekkotron.oikos.feature.security.R
import com.gekkotron.oikos.feature.security.presentation.SecurityEvent
import com.gekkotron.oikos.feature.security.presentation.SecurityUi
import com.gekkotron.oikos.feature.security.presentation.SecurityViewModel
import com.gekkotron.oikos.model.Command

@Composable
fun SecurityRoute(
    windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    viewModel: SecurityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalContext.current.resources
    LaunchedEffect(viewModel, snackbar) {
        viewModel.events.collect { event ->
            when (event) {
                is SecurityEvent.NotConfirmed -> {
                    val result = snackbar.showSnackbar(
                        message = resources.getString(R.string.security_not_confirmed),
                        actionLabel = resources.getString(R.string.security_retry),
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.send(event.id, event.command)
                }
                is SecurityEvent.Rejected -> snackbar.showSnackbar(resources.getString(R.string.security_rejected, event.reason))
                SecurityEvent.SaveFailed -> snackbar.showSnackbar(resources.getString(R.string.security_save_failed))
                SecurityEvent.WaitingForConnection -> snackbar.showSnackbar(resources.getString(R.string.security_waiting))
            }
        }
    }
    SecurityScreen(state, windowInsets, onSend = viewModel::send, onSetHidden = viewModel::setSecurityHidden, snackbarHostState = snackbar)
}

@Composable
fun SecurityScreen(
    state: SecurityUi,
    windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    onSend: (String, Command) -> Unit = { _, _ -> },
    onSetHidden: (String, Boolean) -> Unit = { _, _ -> },
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val layoutDirection = LocalLayoutDirection.current
    var sheetId by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = OikosTheme.colors.content,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = windowInsets,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection) + 16.dp,
                end = padding.calculateEndPadding(layoutDirection) + 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(OikosDimens.gridGap),
        ) {
            item(key = "header") {
                Column(Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.security_title), style = OikosTheme.type.display)
                    Spacer(Modifier.height(8.dp))
                    LinkPill(state.link, onClick = null)
                }
            }
            when {
                state.loading -> Unit
                state.panels.isEmpty() && state.sensors.isEmpty() -> item(key = "empty") {
                    Text(stringResource(R.string.security_empty), style = OikosTheme.type.body, modifier = Modifier.padding(vertical = 24.dp))
                }
                else -> {
                    if (state.panels.isEmpty()) {
                        item(key = "no-panel") {
                            OikosCard(Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.security_no_panel), style = OikosTheme.type.body, modifier = Modifier.padding(16.dp))
                            }
                        }
                    }
                    items(state.panels, key = { "panel-${it.id}" }) { p ->
                        AlarmHero(p.name, p.state, p.available, p.pending, p.stale, p.capabilities, onSend = { onSend(p.id, it) })
                    }
                    item(key = "summary") {
                        Text(
                            summaryText(state.summary),
                            style = OikosTheme.type.body,
                            color = OikosTheme.colors.contentMuted,
                            modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    if (state.sensors.isEmpty()) {
                        item(key = "no-sensors") { Text(stringResource(R.string.security_no_sensors), style = OikosTheme.type.body) }
                    }
                    items(state.sensors, key = { "sensor-${it.id}" }) { row -> SensorRowCard(row, onClick = { sheetId = row.id }) }
                    item(key = "alerts") {
                        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.security_alerts), style = OikosTheme.type.label)
                            Text(stringResource(R.string.security_alerts_empty), style = OikosTheme.type.body, color = OikosTheme.colors.contentMuted)
                        }
                    }
                }
            }
        }
    }
    // A sensor that left Security (hidden here or elsewhere) closes its sheet.
    val sheetRow = sheetId?.let { id -> state.sensors.firstOrNull { it.id == id } }
    if (sheetRow != null) {
        SensorSheet(sheetRow, onHide = { onSetHidden(sheetRow.id, true); sheetId = null }, onDismiss = { sheetId = null })
    }
}
