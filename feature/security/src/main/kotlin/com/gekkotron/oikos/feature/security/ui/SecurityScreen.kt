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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.gekkotron.oikos.designsystem.rememberNow
import java.time.Instant
import com.gekkotron.oikos.designsystem.alarm.AlarmHero
import com.gekkotron.oikos.designsystem.components.LinkPill
import com.gekkotron.oikos.designsystem.components.OikosCard
import com.gekkotron.oikos.feature.security.R
import com.gekkotron.oikos.feature.security.presentation.PendingAction
import com.gekkotron.oikos.feature.security.presentation.SecurityEvent
import com.gekkotron.oikos.feature.security.presentation.SecurityUi
import com.gekkotron.oikos.feature.security.presentation.SecurityViewModel
import com.gekkotron.oikos.feature.security.presentation.SensorSection
import com.gekkotron.oikos.feature.security.presentation.pendingAction
import com.gekkotron.oikos.feature.security.presentation.tap
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.SwitchType
import kotlinx.coroutines.launch

/**
 * [customizeSheet] is Home's override sheet for an entity, passed in by the app so this module does not
 * depend on Home; it reports a failed save through `onSaveFailed`, after the sheet has closed. Null: the
 * sheets offer no "Customize…" link.
 */
@Composable
fun SecurityRoute(
    windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    customizeSheet: (@Composable (id: String, onDismiss: () -> Unit, onSaveFailed: () -> Unit) -> Unit)? = null,
    alertsSection: (@Composable () -> Unit)? = null,
    /** Always composed, outside the lazy list, so effects of the alerts section run while it is scrolled out of view. */
    alertsEffects: (@Composable (snackbar: SnackbarHostState) -> Unit)? = null,
    /** Open the entity's history screen. Null hides the sheet's history link (history off / entity not recorded). */
    onOpenHistory: ((String) -> Unit)? = null,
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
    alertsEffects?.invoke(snackbar)
    val scope = rememberCoroutineScope()
    val saveFailed = { scope.launch { snackbar.showSnackbar(resources.getString(R.string.security_save_failed)) }; Unit }
    SecurityScreen(
        state, windowInsets,
        onSend = viewModel::send,
        onSetHidden = viewModel::setSecurityHidden,
        onSetAction = viewModel::setAction,
        customizeSheet = customizeSheet?.let { sheet -> { id, onDismiss -> sheet(id, onDismiss, saveFailed) } },
        alertsSection = alertsSection,
        snackbarHostState = snackbar,
        onOpenHistory = onOpenHistory,
    )
}

@Composable
fun SecurityScreen(
    state: SecurityUi,
    windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    onSend: (String, Command) -> Unit = { _, _ -> },
    onSetHidden: (String, Boolean) -> Unit = { _, _ -> },
    onSetAction: (String, Boolean, Boolean) -> Unit = { _, _, _ -> },
    customizeSheet: (@Composable (id: String, onDismiss: () -> Unit) -> Unit)? = null,
    alertsSection: (@Composable () -> Unit)? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    /** Open the entity's history screen. Null hides the sheet's history link. */
    onOpenHistory: ((String) -> Unit)? = null,
    /** One minute-ticking clock for every tile and the sheet; tests pass a fixed one. */
    now: Instant = rememberNow().value,
) {
    val layoutDirection = LocalLayoutDirection.current
    var sheetId by rememberSaveable { mutableStateOf<String?>(null) }
    var actionSheetId by rememberSaveable { mutableStateOf<String?>(null) }
    // Captured at tap time so a later state echo cannot change the command the user agreed to. Not saved across
    // process death on purpose: a half-answered confirm shouldn't survive a kill.
    var confirmPending by remember { mutableStateOf<PendingAction?>(null) }
    var customizeId by rememberSaveable { mutableStateOf<String?>(null) }
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
                                            // A stale action can't send: let the view model say so now, not after asking.
                                            val pending = if (row.stale) null else pendingAction(row)
                                            if (pending != null) confirmPending = pending else onSend(row.id, row.tap().command)
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
                        // Two tiles a row, the same height, in a stable alphabetical order; a tile does not jump when its state flips.
                        items(section.rows.chunked(2), key = { "sensors-${it.first().id}" }) { pair ->
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(OikosDimens.gridGap)) {
                                pair.forEach { row -> SensorTile(row, now, onClick = { sheetId = row.id }, Modifier.weight(1f).fillMaxHeight()) }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            // Even a house with no panel or sensor gets its alerts (a camera-only house, say).
            if (!state.loading) alertsSection?.let { section -> item(key = "alerts") { section() } }
        }
    }
    // A sensor that left Security (hidden here or elsewhere) closes its sheet.
    val sheetRow = sheetId?.let { id -> state.sensors.firstOrNull { it.id == id } }
    if (sheetRow != null) {
        SensorSheet(
            sheetRow,
            now,
            onHide = { onSetHidden(sheetRow.id, true); sheetId = null },
            onDismiss = { sheetId = null },
            onCustomize = customizeSheet?.let { { sheetId = null; customizeId = sheetRow.id } },
            onOpenHistory = onOpenHistory?.let { open -> { sheetId = null; open(sheetRow.id) } },
        )
    }
    // An action that left Security closes its sheet.
    val actionRow = actionSheetId?.let { state.action(it) }
    if (actionRow != null) {
        ActionSheet(
            actionRow,
            onSet = { show, ask -> onSetAction(actionRow.id, show, ask); if (!show) actionSheetId = null },
            onDismiss = { actionSheetId = null },
            onCustomize = customizeSheet?.let { { actionSheetId = null; customizeId = actionRow.id } },
        )
    }
    customizeId?.let { id -> customizeSheet?.invoke(id) { customizeId = null } }
    // The dialog answers for the captured command, not for whatever the row now reports. An action that left
    // Security or went unavailable meanwhile closes the dialog unsent.
    val pendingLiveAvailable = confirmPending?.let { state.action(it.id)?.available == true } == true
    if (confirmPending != null && !pendingLiveAvailable) LaunchedEffect(confirmPending) { confirmPending = null }
    val pending = confirmPending
    if (pending != null && pendingLiveAvailable) {
        val button = pending.kind == Kind.BUTTON
        val titleRes = when {
            button -> R.string.security_action_confirm_press
            pending.switchType == SwitchType.DOOR -> R.string.security_action_confirm_open
            pending.switchType == SwitchType.SIREN -> R.string.security_action_confirm_ring
            else -> R.string.security_action_confirm_on
        }
        val actionLabelRes = when {
            button -> R.string.security_action_press
            pending.switchType == SwitchType.DOOR -> R.string.security_action_open
            pending.switchType == SwitchType.SIREN -> R.string.security_action_ring
            else -> R.string.security_action_turn_on
        }
        AlertDialog(
            onDismissRequest = { confirmPending = null },
            title = { Text(stringResource(titleRes, pending.name)) },
            confirmButton = {
                TextButton(onClick = { confirmPending = null; onSend(pending.id, pending.command) }) {
                    Text(stringResource(actionLabelRes))
                }
            },
            dismissButton = { TextButton(onClick = { confirmPending = null }) { Text(stringResource(R.string.security_cancel)) } },
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
