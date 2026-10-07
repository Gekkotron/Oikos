package com.gekkotron.oikos.feature.alerts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.components.ChipItem
import com.gekkotron.oikos.designsystem.components.RoomChips
import com.gekkotron.oikos.feature.alerts.R
import com.gekkotron.oikos.feature.alerts.presentation.AlertsUi
import com.gekkotron.oikos.feature.alerts.presentation.AlertsViewModel
import com.gekkotron.oikos.feature.alerts.presentation.DayLabel
import com.gekkotron.oikos.push.AlertChannels
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun AlertsRoute(
    onBack: () -> Unit,
    windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    deviceSheet: (@Composable (id: String, onDismiss: () -> Unit) -> Unit)? = null,
    viewModel: AlertsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val now by rememberNow()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    AlertsScreen(
        state, now,
        onSelect = viewModel::select, onOpen = { openId = it }, onClearAll = viewModel::clearAll, onBack = onBack,
        windowInsets = windowInsets,
    )
    openId?.let { id -> AlertDetailRoute(id, onDismiss = { openId = null }, deviceSheet = deviceSheet) }
}

@Composable
fun AlertsScreen(
    state: AlertsUi,
    now: Instant,
    onSelect: (AlertChannel?) -> Unit,
    onOpen: (String) -> Unit,
    onClearAll: () -> Unit,
    onBack: () -> Unit,
    windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    val allLabel = stringResource(R.string.alerts_filter_all)
    val chips = listOf(ChipItem(ALL_KEY, allLabel)) + AlertChannel.entries.map { ChipItem(it.id, stringResource(AlertChannels.nameRes(it))) }
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = OikosTheme.colors.content,
        contentWindowInsets = windowInsets,
        topBar = { AlertsTopBar(onBack, canClear = state.sections.isNotEmpty(), onClearAll = { confirm = true }) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(OikosDimens.gridGap),
        ) {
            item(key = "chips") {
                RoomChips(chips, state.filter?.id ?: ALL_KEY, onSelect = { key -> onSelect(AlertChannel.entries.firstOrNull { it.id == key }) })
            }
            when {
                state.loading -> Unit
                state.sections.isEmpty() -> item(key = "empty") {
                    Text(
                        stringResource(if (state.filter == null) R.string.alerts_empty else R.string.alerts_empty_filter),
                        style = OikosTheme.type.body,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
                else -> state.sections.forEach { section ->
                    item(key = "day-${section.label}") {
                        Text(
                            dayTitle(section.label),
                            style = OikosTheme.type.label,
                            color = OikosTheme.colors.contentMuted,
                            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                        )
                    }
                    items(section.alerts, key = { it.id }) { alert -> AlertRow(alert, now, onClick = { onOpen(alert.id) }) }
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.alerts_clear_title)) },
            text = { Text(stringResource(R.string.alerts_clear_body)) },
            confirmButton = { TextButton(onClick = { confirm = false; onClearAll() }) { Text(stringResource(R.string.alerts_clear_confirm)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.alerts_cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlertsTopBar(onBack: () -> Unit, canClear: Boolean, onClearAll: () -> Unit) {
    val backLabel = stringResource(R.string.alerts_back)
    val moreLabel = stringResource(R.string.alerts_more)
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(stringResource(R.string.alerts_title), style = OikosTheme.type.display) },
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = backLabel }) {
                Text("←", style = OikosTheme.type.cardValue, modifier = Modifier.clearAndSetSemantics {})
            }
        },
        actions = {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = moreLabel }) {
                    Text("⋮", style = OikosTheme.type.cardValue, modifier = Modifier.clearAndSetSemantics {})
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.alerts_clear_all)) },
                        enabled = canClear,
                        onClick = { menu = false; onClearAll() },
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = OikosTheme.colors.content,
            navigationIconContentColor = OikosTheme.colors.content,
            actionIconContentColor = OikosTheme.colors.content,
        ),
    )
}

@Composable
private fun dayTitle(label: DayLabel): String = when (label) {
    DayLabel.Today -> stringResource(R.string.alerts_today)
    DayLabel.Yesterday -> stringResource(R.string.alerts_yesterday)
    is DayLabel.On -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(LocalConfiguration.current.locales[0]).format(label.date)
}

private const val ALL_KEY = "all"
