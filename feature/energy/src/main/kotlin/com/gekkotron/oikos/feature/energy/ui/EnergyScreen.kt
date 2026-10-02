package com.gekkotron.oikos.feature.energy.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.components.LinkPill
import com.gekkotron.oikos.designsystem.components.OikosCard
import com.gekkotron.oikos.feature.energy.R
import com.gekkotron.oikos.feature.energy.presentation.EnergyFormat
import com.gekkotron.oikos.feature.energy.presentation.EnergyHint
import com.gekkotron.oikos.feature.energy.presentation.EnergyNode
import com.gekkotron.oikos.feature.energy.presentation.EnergyUiState
import com.gekkotron.oikos.feature.energy.presentation.EnergyViewModel
import com.gekkotron.oikos.feature.energy.presentation.Flow
import com.gekkotron.oikos.feature.energy.presentation.NodeKind
import com.gekkotron.oikos.feature.energy.presentation.NodeStatus
import java.util.Locale
import com.gekkotron.oikos.designsystem.R as DsR

@Composable
fun EnergyRoute(
    onOpenHome: () -> Unit,
    windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    viewModel: EnergyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EnergyScreen(state, onOpenHome, windowInsets)
}

@Composable
fun EnergyScreen(
    state: EnergyUiState,
    onOpenHome: () -> Unit,
    windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    locale: Locale = Locale.getDefault(),
) {
    val layoutDirection = LocalLayoutDirection.current
    var consumersSheet by rememberSaveable { mutableStateOf(false) }
    Scaffold(containerColor = Color.Transparent, contentColor = OikosTheme.colors.content, contentWindowInsets = windowInsets) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection) + 16.dp,
                end = padding.calculateEndPadding(layoutDirection) + 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(OikosDimens.gridGap),
            verticalArrangement = Arrangement.spacedBy(OikosDimens.gridGap),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) { EnergyHeader(state) }
            when {
                state.loading -> Unit
                state.empty -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 24.dp)) {
                        Text(stringResource(R.string.energy_empty), style = OikosTheme.type.body)
                        Button(onClick = onOpenHome, modifier = Modifier.heightIn(min = OikosDimens.minTouch)) { Text(stringResource(R.string.energy_go_home)) }
                    }
                }
                else -> {
                    if (state.hint == EnergyHint.CHECK_SIGN) {
                        item(key = "hint", span = { GridItemSpan(maxLineSpan) }) {
                            Text(stringResource(R.string.energy_check_sign), style = OikosTheme.type.label, color = OikosTheme.colors.warning)
                        }
                    }
                    item(key = "diagram", span = { GridItemSpan(maxLineSpan) }) {
                        OikosCard(Modifier.fillMaxWidth()) {
                            FlowDiagram(state, locale, onMore = { consumersSheet = true }, modifier = Modifier.padding(8.dp))
                        }
                    }
                    items(state.cards, key = { "card-${it.id}" }) { node -> EnergyValueCard(node, locale) }
                }
            }
        }
    }
    if (consumersSheet) ConsumersSheet(state.consumers, locale, onDismiss = { consumersSheet = false })
}

@Composable
private fun EnergyHeader(state: EnergyUiState) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.energy_title), style = OikosTheme.type.display)
        Text(
            state.selfConsumption?.let { stringResource(R.string.energy_live_self, it) } ?: stringResource(R.string.energy_live),
            style = OikosTheme.type.body,
            color = OikosTheme.colors.contentMuted,
        )
        Spacer(Modifier.height(8.dp))
        LinkPill(state.link, onClick = null)
    }
}

@Composable
private fun EnergyValueCard(node: EnergyNode, locale: Locale) {
    val staleText = stringResource(R.string.energy_stale)
    OikosCard(
        Modifier
            .fillMaxWidth()
            .testTag("energy-card-${node.id}")
            .alpha(statusAlpha(node.status))
            .semantics(mergeDescendants = true) { if (node.status == NodeStatus.STALE) stateDescription = staleText },
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(nodeLabel(node), style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
            Text(nodeValueText(node, locale), style = OikosTheme.type.body)
        }
    }
}

@Composable
internal fun nodeLabel(node: EnergyNode): String = node.name ?: stringResource(
    when (node.kind) {
        NodeKind.SOLAR -> DsR.string.energy_solar
        NodeKind.GRID -> DsR.string.energy_grid
        NodeKind.BATTERY -> DsR.string.energy_battery
        NodeKind.HOUSE -> DsR.string.energy_house
        NodeKind.WATER -> DsR.string.energy_water
        NodeKind.CONSUMER, NodeKind.MORE -> DsR.string.energy_consumer
    },
)

/** "—", "Unavailable", or the value; grid and battery get ↙ (into the house) or ↗ (out of it). */
@Composable
internal fun nodeValueText(node: EnergyNode, locale: Locale): String {
    if (node.status == NodeStatus.UNAVAILABLE) return stringResource(R.string.energy_unavailable)
    val v = node.value ?: return stringResource(R.string.energy_unknown)
    if (node.kind == NodeKind.WATER) return EnergyFormat.amount(v, node.unit, locale)
    val arrow = if (node.kind == NodeKind.GRID || node.kind == NodeKind.BATTERY) {
        when (node.flow) {
            Flow.IN -> "↙ "
            Flow.OUT -> "↗ "
            Flow.IDLE -> ""
        }
    } else {
        ""
    }
    return arrow + EnergyFormat.power(v, locale)
}
