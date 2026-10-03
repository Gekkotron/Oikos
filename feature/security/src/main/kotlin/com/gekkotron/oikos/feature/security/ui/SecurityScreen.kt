package com.gekkotron.oikos.feature.security.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.heading
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
import com.gekkotron.oikos.feature.security.presentation.SensorSection
import com.gekkotron.oikos.feature.security.presentation.tap
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Kind

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
    SecurityScreen(state, windowInsets, onSend = viewModel::send, onSetHidden = viewModel::setSecurityHidden, onSetAction = viewModel::setAction, snackbarHostState = snackbar)
}

@Composable
fun SecurityScreen(
    state: SecurityUi,
    windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    onSend: (String, Command) -> Unit = { _, _ -> },
    onSetHidden: (String, Boolean) -> Unit = { _, _ -> },
    onSetAction: (String, Boolean, Boolean) -> Unit = { _, _, _ -> },
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val layoutDirection = LocalLayoutDirection.current
    var sheetId by rememberSaveable { mutableStateOf<String?>(null) }
    var actionSheetId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmId by rememberSaveable { mutableStateOf<String?>(null) }
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
                state.isEmpty -> item(key = "empty") {
                    Text(stringResource(R.string.security_empty), style = OikosTheme.type.body, modifier = Modifier.padding(vertical = 24.dp))
                }
                else -> {
                    if (state.panels.isEmpty()) {
                        item(key = "no-panel") {
                            OikosCard(Modifier.fillMaxWidth(), minHeight = 0.dp) {
                                Text(stringResource(R.string.security_no_panel), style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
                            }
                        }
                    }
                    items(state.panels, key = { "panel-${it.id}" }) { p ->
                        AlarmHero(p.name, p.state, p.available, p.pending, p.stale, p.capabilities, onSend = { onSend(p.id, it) })
                    }
                    if (state.actions.isNotEmpty()) {
                        item(key = "section-actions") {
                            Text(
                                stringResource(R.string.security_section_actions),
                                style = OikosTheme.type.label,
                                color = OikosTheme.colors.contentMuted,
                                modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                            )
                        }
                        items(state.actions.chunked(2), key = { "actions-${it.first().id}" }) { pair ->
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(OikosDimens.gridGap)) {
                                pair.forEach { row ->
                                    ActionTile(
                                        row,
                                        onClick = {
                                            val tap = row.tap()
                                            // A stale action can't send: let the view model say so now, not after asking.
                                            if (tap.confirm && !row.stale) confirmId = row.id else onSend(row.id, tap.command)
                                        },
                                        onLongClick = { actionSheetId = row.id },
                                        Modifier.weight(1f).fillMaxHeight(),
                                    )
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
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
                    state.sections.forEach { section ->
                        item(key = "section-${section.section}") {
                            Text(
                                sectionTitle(section.section),
                                style = OikosTheme.type.label,
                                color = OikosTheme.colors.contentMuted,
                                modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                            )
                        }
                        // Two tiles a row, the same height, in tab order (active first, top-left).
                        items(section.rows.chunked(2), key = { "sensors-${it.first().id}" }) { pair ->
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(OikosDimens.gridGap)) {
                                pair.forEach { row -> SensorTile(row, onClick = { sheetId = row.id }, Modifier.weight(1f).fillMaxHeight()) }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
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
    // An action that left Security closes its sheet.
    val actionRow = actionSheetId?.let { state.action(it) }
    if (actionRow != null) {
        ActionSheet(
            actionRow,
            onSet = { show, ask -> onSetAction(actionRow.id, show, ask); if (!show) actionSheetId = null },
            onDismiss = { actionSheetId = null },
        )
    }
    // Asked at tap time; an action that left Security or went unavailable meanwhile closes the dialog unsent.
    val confirmRow = confirmId?.let { state.action(it) }?.takeIf { it.available }
    if (confirmId != null && confirmRow == null) LaunchedEffect(confirmId) { confirmId = null }
    if (confirmRow != null) {
        val button = confirmRow.kind == Kind.BUTTON
        AlertDialog(
            onDismissRequest = { confirmId = null },
            title = { Text(stringResource(if (button) R.string.security_action_confirm_press else R.string.security_action_confirm_on, confirmRow.name)) },
            confirmButton = {
                TextButton(onClick = { confirmId = null; onSend(confirmRow.id, if (button) Command.Press else Command.TurnOn()) }) {
                    Text(stringResource(if (button) R.string.security_action_press else R.string.security_action_turn_on))
                }
            },
            dismissButton = { TextButton(onClick = { confirmId = null }) { Text(stringResource(R.string.security_cancel)) } },
        )
    }
}

@Composable
private fun sectionTitle(section: SensorSection): String = stringResource(
    when (section) {
        SensorSection.FIRE -> R.string.security_section_fire
        SensorSection.WATER -> R.string.security_section_water
        SensorSection.OPENINGS -> R.string.security_section_openings
        SensorSection.MOTION -> R.string.security_section_motion
        SensorSection.OTHER -> R.string.security_section_other
    },
)
