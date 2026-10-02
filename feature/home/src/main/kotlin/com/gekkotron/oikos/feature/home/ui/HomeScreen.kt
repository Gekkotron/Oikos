package com.gekkotron.oikos.feature.home.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.ScaffoldDefaults
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.components.ChipItem
import com.gekkotron.oikos.designsystem.components.LinkPill
import com.gekkotron.oikos.designsystem.components.RoomChips
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.feature.home.presentation.CardUi
import com.gekkotron.oikos.feature.home.presentation.HomeUiState
import com.gekkotron.oikos.feature.home.presentation.HomeViewModel
import com.gekkotron.oikos.feature.home.presentation.RoomFilter
import com.gekkotron.oikos.feature.home.presentation.TapAction
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.transport.Route
import java.time.LocalTime

@Composable
fun HomeRoute(
    onSetUpAgain: () -> Unit = {},
    onAssignRooms: () -> Unit = {},
    route: Route? = null,
    onRouteChange: (Route) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    HomeEventsEffect(viewModel.events, snackbar, onRetry = viewModel::send)
    HomeScreen(
        state, viewModel::select, viewModel::tap, viewModel::send, snackbar,
        onSetUpAgain = onSetUpAgain,
        onCustomize = viewModel::customize,
        onAssignRooms = onAssignRooms,
        route = route,
        onRouteChange = onRouteChange,
    )
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onSelect: (RoomFilter) -> Unit,
    onTap: (CardUi) -> Unit,
    onSend: (String, Command) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    now: LocalTime = LocalTime.now(),
    windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    onSetUpAgain: () -> Unit = {},
    onCustomize: (String, List<Area>, UiOverride) -> Unit = { _, _, _ -> },
    onAssignRooms: () -> Unit = {},
    route: Route? = null,
    onRouteChange: (Route) -> Unit = {},
) {
    val layoutDirection = LocalLayoutDirection.current
    var sheetId by rememberSaveable { mutableStateOf<String?>(null) }
    var customizeId by rememberSaveable { mutableStateOf<String?>(null) }
    var linkSheet by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = OikosTheme.colors.content,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = windowInsets,
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
                    start = padding.calculateStartPadding(layoutDirection) + 16.dp,
                    end = padding.calculateEndPadding(layoutDirection) + 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(OikosDimens.gridGap),
                verticalArrangement = Arrangement.spacedBy(OikosDimens.gridGap),
            ) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) { HomeHeader(state, now, onPillClick = { linkSheet = true }, onHeroLongClick = { state.hero?.let { customizeId = it.id } }) }
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
                        onLongClick = { if (card.hasSheet) sheetId = card.id else customizeId = card.id },
                    )
                }
            }
            // Edge-to-edge: keep scrolled cards from colliding with the clock and status icons.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(padding.calculateTopPadding())
                    .background(OikosTheme.colors.backgroundTop.copy(alpha = 0.92f))
                    .testTag("status-scrim"),
            )
        }
    }
    val sheetCard = sheetId?.let(state::card)
    if (sheetCard != null) {
        ControlSheet(
            sheetCard,
            onSend = { onSend(sheetCard.id, it) },
            onCustomize = { sheetId = null; customizeId = sheetCard.id },
            onDismiss = { sheetId = null },
        )
    }
    val customizeCard = customizeId?.let(state::card)
    if (customizeCard != null) {
        CustomizeSheet(
            customizeCard,
            state.rooms,
            onSave = { newRooms, override -> onCustomize(customizeCard.id, newRooms, override) },
            onDismiss = { customizeId = null },
        )
    }
    if (linkSheet) {
        LinkSheet(
            state.link,
            route = route,
            onRouteChange = onRouteChange,
            onSetUpAgain = { linkSheet = false; onSetUpAgain() },
            onAssignRooms = { linkSheet = false; onAssignRooms() },
            onDismiss = { linkSheet = false },
        )
    }
}

@Composable
private fun HomeHeader(state: HomeUiState, now: LocalTime, onPillClick: () -> Unit = {}, onHeroLongClick: () -> Unit = {}) {
    val haptics = LocalHapticFeedback.current
    val customizeLabel = stringResource(R.string.action_customize)
    val staleText = stringResource(R.string.card_stale)
    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(greeting(now), style = OikosTheme.type.display, modifier = Modifier.weight(1f))
            state.hero?.let { hero ->
                Text(
                    cardValueText(hero.value),
                    style = OikosTheme.type.display,
                    // Long-press only, so it must not expose a click action TalkBack would announce as
                    // "double-tap to activate" and then do nothing (spec §5.2); see OikosCard for the same pattern.
                    modifier = Modifier
                        .testTag("hero")
                        .alpha(if (hero.stale) 0.6f else 1f)
                        .pointerInput(Unit) {
                            detectTapGestures(onLongPress = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onHeroLongClick()
                            })
                        }
                        .semantics {
                            onLongClick(label = customizeLabel) { onHeroLongClick(); true }
                            if (hero.stale) stateDescription = staleText
                        },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LinkPill(state.link, onClick = onPillClick)
    }
}

@Composable
private fun HomeChips(state: HomeUiState, onSelect: (RoomFilter) -> Unit) {
    val all = stringResource(R.string.chip_all)
    val favorites = stringResource(R.string.chip_favorites)
    val unassigned = stringResource(R.string.area_unassigned)
    val hidden = stringResource(R.string.chip_hidden, state.hiddenCount)
    val items = state.chips.map { chip ->
        val label = chip.label ?: when (chip.filter) {
            RoomFilter.All -> all
            RoomFilter.Favorites -> favorites
            RoomFilter.Hidden -> hidden
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
