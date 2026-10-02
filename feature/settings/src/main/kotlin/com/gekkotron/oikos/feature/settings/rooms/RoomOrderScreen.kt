package com.gekkotron.oikos.feature.settings.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.components.OikosCard
import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.designsystem.icons.StaticIcon
import com.gekkotron.oikos.feature.settings.R
import com.gekkotron.oikos.feature.settings.ui.SettingsTopBar
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun RoomOrderRoute(onAssignRooms: () -> Unit, onBack: () -> Unit, viewModel: RoomOrderViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var resetKey by remember { mutableIntStateOf(0) }
    val failed = stringResource(R.string.rooms_save_failed)
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                RoomOrderEvent.SaveFailed -> {
                    resetKey++ // the source rolled back to an equal list, so state alone would not re-emit
                    scope.launch { snackbar.showSnackbar(failed) } // never block the collector: the next failure must reset too
                }
            }
        }
    }
    RoomOrderScreen(state, viewModel::commit, viewModel::move, onAssignRooms, onBack, snackbar, resetKey)
}

@Composable
fun RoomOrderScreen(
    state: RoomOrderUi,
    onCommit: (List<String>) -> Unit,
    onMove: (String, Int) -> Unit,
    onAssignRooms: () -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    resetKey: Int = 0,
) {
    // The list the user is dragging; it follows the source whenever the source (or a failed save) moves on.
    var list by remember(state.rooms, resetKey) { mutableStateOf(state.rooms) }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = list.indexOfFirst { it.id == from.key }
        val toIndex = list.indexOfFirst { it.id == to.key }
        if (fromIndex >= 0 && toIndex >= 0) list = list.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
    }
    val moveUp = stringResource(R.string.rooms_move_up)
    val moveDown = stringResource(R.string.rooms_move_down)
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = OikosTheme.colors.content,
        topBar = { SettingsTopBar(stringResource(R.string.settings_rooms), onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "hint") {
                Text(stringResource(R.string.rooms_order_hint), style = OikosTheme.type.body, color = OikosTheme.colors.contentMuted, modifier = Modifier.padding(bottom = 8.dp))
            }
            items(list, key = { it.id }) { room ->
                val index = list.indexOf(room)
                ReorderableItem(reorderState, key = room.id) {
                    RoomRow(
                        room.name,
                        modifier = Modifier.semantics(mergeDescendants = true) {
                            customActions = listOfNotNull(
                                CustomAccessibilityAction(moveUp) { onMove(room.id, -1); true }.takeIf { index > 0 },
                                CustomAccessibilityAction(moveDown) { onMove(room.id, 1); true }.takeIf { index < list.lastIndex },
                            )
                        },
                    ) {
                        Box(
                            Modifier
                                .size(OikosDimens.minTouch)
                                .draggableHandle(onDragStopped = { onCommit(list.map { it.id }) })
                                .clearAndSetSemantics { testTag = "drag-handle-${room.id}" },
                            contentAlignment = Alignment.Center,
                        ) {
                            StaticIcon(OikosGlyph.DRAG_HANDLE, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
            if (state.hasUnassigned) {
                item(key = "unassigned") { RoomRow(stringResource(R.string.rooms_unassigned)) {} }
            }
            item(key = "assign") {
                OutlinedButton(onClick = onAssignRooms, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text(stringResource(R.string.rooms_assign)) }
            }
        }
    }
}

@Composable
private fun RoomRow(name: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit) {
    OikosCard(modifier = modifier.fillMaxWidth(), minHeight = OikosDimens.minTouch) {
        Row(Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = OikosTheme.type.body, modifier = Modifier.weight(1f))
            trailing()
        }
    }
}
