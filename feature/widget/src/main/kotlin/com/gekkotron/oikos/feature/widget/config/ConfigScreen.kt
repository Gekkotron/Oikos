package com.gekkotron.oikos.feature.widget.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.alarm.armLabel
import com.gekkotron.oikos.feature.widget.R
import com.gekkotron.oikos.feature.widget.model.WidgetConfig
import com.gekkotron.oikos.model.AlarmMode

/** The entity picker (widgets spec §2.1): selected entities in widget order, then every entity grouped by area. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    state: ConfigUiState,
    onSearch: (String) -> Unit,
    onToggle: (String) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onArmMode: (AlarmMode) -> Unit,
    onDone: () -> Unit,
    onOpenApp: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.widget_config_title)) },
                actions = {
                    if (!state.notConfigured) {
                        TextButton(onClick = onDone, enabled = state.selected.isNotEmpty()) { Text(stringResource(R.string.widget_config_save)) }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.notConfigured -> NotConfigured(onOpenApp, Modifier.align(Alignment.Center))
                else -> Picker(state, onSearch, onToggle, onMove, onArmMode)
            }
        }
    }
}

@Composable
private fun NotConfigured(onOpenApp: () -> Unit, modifier: Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.widget_set_up), style = MaterialTheme.typography.titleMedium)
        Button(onClick = onOpenApp) { Text(stringResource(R.string.widget_config_open_app)) }
    }
}

@Composable
private fun Picker(
    state: ConfigUiState,
    onSearch: (String) -> Unit,
    onToggle: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onArmMode: (AlarmMode) -> Unit,
) {
    val byId = state.allRows.associateBy { it.id }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onSearch,
                    label = { Text(stringResource(R.string.widget_config_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.widget_config_hint, WidgetConfig.MAX_ENTITIES, state.fitCount),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.offline) {
                    Text(stringResource(R.string.widget_config_offline), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (state.selected.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.widget_config_selected)) }
            items(state.selected.size, key = { "selected-${state.selected[it]}" }) { index ->
                val id = state.selected[index]
                ListItem(
                    headlineContent = { Text(byId[id]?.name ?: id) },
                    supportingContent = byId[id]?.let { row -> { Text(row.areaName) } },
                    trailingContent = {
                        val up = stringResource(R.string.widget_config_move_up)
                        val down = stringResource(R.string.widget_config_move_down)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onMove(index, index - 1) }, enabled = index > 0, modifier = Modifier.semantics { contentDescription = up }) { Text("↑") }
                            IconButton(onClick = { onMove(index, index + 1) }, enabled = index < state.selected.lastIndex, modifier = Modifier.semantics { contentDescription = down }) { Text("↓") }
                        }
                    },
                )
            }
            state.alarmRow?.let { alarm ->
                item(key = "arm-mode") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(stringResource(R.string.widget_config_arm_mode, alarm.name), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AlarmMode.entries.filter { it in alarm.alarmModes }.forEach { mode ->
                                FilterChip(selected = state.armMode == mode, onClick = { onArmMode(mode) }, label = { Text(stringResource(armLabel(mode))) })
                            }
                        }
                    }
                }
            }
        }
        state.rows.groupBy { it.areaName }.forEach { (area, rows) ->
            item(key = "area-$area") { SectionHeader(area) }
            items(rows, key = { "row-${it.id}" }) { row ->
                ListItem(
                    headlineContent = { Text(row.name) },
                    leadingContent = { Checkbox(checked = row.id in state.selected, onCheckedChange = null) },
                    modifier = Modifier.clickable { onToggle(row.id) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}
