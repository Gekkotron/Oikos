package com.gekkotron.oikos.feature.home.rooms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.feature.home.ui.RoomPicker
import com.gekkotron.oikos.model.UNASSIGNED_AREA

@Composable
fun RoomsRoute(onDone: () -> Unit, viewModel: RoomsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalContext.current.resources
    LaunchedEffect(viewModel, snackbar) {
        viewModel.events.collect { snackbar.showSnackbar(resources.getString(R.string.home_save_failed)) }
    }
    RoomsScreen(state, viewModel::assign, viewModel::createRoom, onDone, snackbar)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomsScreen(
    state: RoomsUiState,
    onAssign: (String, String) -> Unit,
    onCreateRoom: (String) -> String?,
    onDone: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    var pickingId by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = OikosTheme.colors.content,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = padding.calculateTopPadding() + 16.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "title") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.rooms_title), style = OikosTheme.type.display.copy(fontSize = 26.sp))
                    Text(stringResource(R.string.rooms_subtitle), style = OikosTheme.type.body)
                    if (state.loading) {
                        Text(stringResource(R.string.home_discovering), style = OikosTheme.type.body)
                    } else {
                        Text(pluralStringResource(R.plurals.rooms_progress, state.rows.size, state.assigned, state.rows.size), style = OikosTheme.type.label)
                    }
                }
            }
            items(state.rows, key = { it.id }) { row -> RoomRow(row) { pickingId = row.id } }
            item(key = "finish") {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = OikosDimens.minTouch)) {
                    Text(stringResource(if (state.changed) R.string.rooms_done else R.string.rooms_skip))
                }
            }
        }
    }
    val picking = pickingId?.let { id -> state.rows.firstOrNull { it.id == id } }
    if (picking != null) {
        ModalBottomSheet(onDismissRequest = { pickingId = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.rooms_pick_title, picking.name), style = OikosTheme.type.display.copy(fontSize = 22.sp))
                RoomPicker(
                    rooms = state.rooms,
                    selectedId = picking.areaId,
                    onSelect = { onAssign(picking.id, it); pickingId = null },
                    onCreate = onCreateRoom,
                )
            }
        }
    }
}

@Composable
private fun RoomRow(row: RoomRowUi, onClick: () -> Unit) {
    val room = when (row.areaId) {
        null -> stringResource(R.string.rooms_several)
        UNASSIGNED_AREA -> stringResource(R.string.room_none)
        else -> row.areaName ?: row.areaId
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.name, style = OikosTheme.type.body)
            if (row.entityIds.size > 1) Text(pluralStringResource(R.plurals.rooms_entities, row.entityIds.size, row.entityIds.size), style = OikosTheme.type.label)
        }
        Spacer(Modifier.width(12.dp))
        Text(room, style = OikosTheme.type.label)
    }
}
