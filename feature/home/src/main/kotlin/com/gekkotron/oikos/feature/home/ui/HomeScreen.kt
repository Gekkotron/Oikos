package com.gekkotron.oikos.feature.home.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.components.ChipItem
import com.gekkotron.oikos.designsystem.components.RoomChips
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.feature.home.presentation.CardUi
import com.gekkotron.oikos.feature.home.presentation.HomeUiState
import com.gekkotron.oikos.feature.home.presentation.HomeViewModel
import com.gekkotron.oikos.feature.home.presentation.RoomFilter
import com.gekkotron.oikos.feature.home.presentation.TapAction
import com.gekkotron.oikos.model.Command
import java.time.LocalTime

@Composable
fun HomeRoute(viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    HomeEventsEffect(viewModel.events, snackbar, onRetry = viewModel::send)
    HomeScreen(state, viewModel::select, viewModel::tap, viewModel::send, snackbar)
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onSelect: (RoomFilter) -> Unit,
    onTap: (CardUi) -> Unit,
    onSend: (String, Command) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    now: LocalTime = LocalTime.now(),
) {
    var sheetId by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = OikosTheme.colors.content,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = when {
                maxWidth < 600.dp -> 2
                maxWidth < 840.dp -> 3
                else -> 4
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(OikosDimens.gridGap),
                verticalArrangement = Arrangement.spacedBy(OikosDimens.gridGap),
            ) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) { HomeHeader(state, now) }
                item(key = "chips", span = { GridItemSpan(maxLineSpan) }) { HomeChips(state, onSelect) }
                if (state.loading) {
                    item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(R.string.home_discovering), style = OikosTheme.type.body, modifier = Modifier.padding(vertical = 24.dp))
                    }
                }
                items(state.cards, key = { it.id }, span = { GridItemSpan(if (it.wide) minOf(2, maxLineSpan) else 1) }) { card ->
                    EntityCard(
                        card = card,
                        onClick = { if (card.tap == TapAction.OPEN_SHEET) sheetId = card.id else onTap(card) },
                        onLongClick = if (card.hasSheet) ({ sheetId = card.id }) else null,
                    )
                }
            }
        }
    }
    val sheetCard = sheetId?.let(state::card)
    if (sheetCard != null) {
        ControlSheet(sheetCard, onSend = { onSend(sheetCard.id, it) }, onDismiss = { sheetId = null })
    }
}

@Composable
private fun HomeHeader(state: HomeUiState, now: LocalTime) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(greeting(now), style = OikosTheme.type.display, modifier = Modifier.weight(1f))
            state.hero?.let { Text(cardValueText(it.value), style = OikosTheme.type.display) }
        }
        Spacer(Modifier.height(8.dp))
        LinkPill(state.link)
    }
}

@Composable
private fun HomeChips(state: HomeUiState, onSelect: (RoomFilter) -> Unit) {
    val all = stringResource(R.string.chip_all)
    val favorites = stringResource(R.string.chip_favorites)
    val unassigned = stringResource(R.string.area_unassigned)
    val items = state.chips.map { chip ->
        val label = chip.label ?: when (chip.filter) {
            RoomFilter.All -> all
            RoomFilter.Favorites -> favorites
            is RoomFilter.Area -> unassigned
        }
        ChipItem(chip.filter.key, label)
    }
    RoomChips(items, state.selected.key, onSelect = { onSelect(RoomFilter.fromKey(it)) })
}

@Composable
private fun greeting(now: LocalTime): String = stringResource(
    when (now.hour) {
        in 5..11 -> R.string.greeting_morning
        in 12..17 -> R.string.greeting_afternoon
        else -> R.string.greeting_evening
    },
)
