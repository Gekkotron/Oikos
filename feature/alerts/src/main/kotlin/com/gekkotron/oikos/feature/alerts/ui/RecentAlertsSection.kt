package com.gekkotron.oikos.feature.alerts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.gekkotron.oikos.designsystem.rememberNow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.components.OikosCard
import com.gekkotron.oikos.designsystem.permissions.rememberNotificationEnabler
import com.gekkotron.oikos.feature.alerts.R
import com.gekkotron.oikos.feature.alerts.presentation.RecentAlertsViewModel
import kotlinx.coroutines.flow.filter
import java.time.Instant

/** The Security tab's "Recent alerts" (push alerts spec §7.1). Stateless; [RecentAlertsRoute] feeds it. */
@Composable
fun RecentAlertsSection(
    alerts: List<Alert>,
    now: Instant,
    notificationsOff: Boolean,
    onTurnOn: () -> Unit,
    onOpen: (String) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(OikosDimens.gridGap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.alerts_recent),
                style = OikosTheme.type.label,
                color = OikosTheme.colors.contentMuted,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            TextButton(onClick = onSeeAll) { Text(stringResource(R.string.alerts_see_all)) }
        }
        if (notificationsOff) NotificationsBanner(onTurnOn)
        if (alerts.isEmpty()) {
            Text(stringResource(R.string.alerts_empty), style = OikosTheme.type.body, color = OikosTheme.colors.contentMuted)
        } else {
            alerts.forEach { alert -> AlertRow(alert, now, onClick = { onOpen(alert.id) }) }
        }
    }
}

@Composable
private fun NotificationsBanner(onTurnOn: () -> Unit) {
    OikosCard(Modifier.fillMaxWidth(), accent = true, minHeight = 0.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.alerts_notifications_off), style = OikosTheme.type.label, modifier = Modifier.weight(1f))
            TextButton(onClick = onTurnOn) { Text(stringResource(R.string.alerts_turn_on)) }
        }
    }
}

/**
 * The Security tab's always-composed effects, kept out of the lazy list so they run while the section is below the
 * fold: the permission is re-read on every resume, and the catch-up message is shown on [snackbar] once per sync
 * (acknowledged first, so a second sync while the snackbar is up shows its own message after it).
 */
@Composable
fun RecentAlertsEffects(snackbar: SnackbarHostState, viewModel: RecentAlertsViewModel = hiltViewModel()) {
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshPermission()
        onPauseOrDispose {}
    }
    val resources = LocalContext.current.resources
    LaunchedEffect(viewModel, snackbar) {
        viewModel.catchUp.filter { it > 0 }.collect { count ->
            viewModel.acknowledgeCatchUp()
            snackbar.showSnackbar(resources.getQuantityString(R.plurals.alerts_catch_up, count, count))
        }
    }
}

/** Wires the section to Room and the push status; the effects live in [RecentAlertsEffects]. */
@Composable
fun RecentAlertsRoute(
    onOpenAll: () -> Unit,
    deviceSheet: (@Composable (id: String, onDismiss: () -> Unit) -> Unit)? = null,
    viewModel: RecentAlertsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val now by rememberNow()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val turnOn = rememberNotificationEnabler(onResult = viewModel::refreshPermission)
    RecentAlertsSection(state.alerts, now, state.notificationsOff, onTurnOn = turnOn, onOpen = { openId = it }, onSeeAll = onOpenAll)
    openId?.let { id -> AlertDetailRoute(id, onDismiss = { openId = null }, deviceSheet = deviceSheet) }
}
