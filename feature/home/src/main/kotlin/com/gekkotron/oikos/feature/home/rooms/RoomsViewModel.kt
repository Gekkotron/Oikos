package com.gekkotron.oikos.feature.home.rooms

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.feature.home.presentation.AreaNames
import com.gekkotron.oikos.feature.home.presentation.DefaultDispatcher
import com.gekkotron.oikos.feature.home.presentation.OverrideEditor
import com.gekkotron.oikos.source.EntitySource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** The Rooms step (spec §5.2 step 5): runs on the live source, so every choice is written at once. */
@HiltViewModel
class RoomsViewModel @Inject constructor(
    private val source: EntitySource,
    private val savedState: SavedStateHandle,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val editor = OverrideEditor(source)

    val state: StateFlow<RoomsUiState> = combine(
        source.entities,
        source.devices,
        source.areas,
        savedState.getStateFlow(KEY_CHANGED, false),
    ) { entities, devices, areas, changed ->
        RoomsUiBuilder.build(entities, devices, areas, Locale.getDefault()).copy(changed = changed)
    }.conflate().flowOn(defaultDispatcher).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoomsUiState())

    private val _events = Channel<RoomsEvent>(Channel.BUFFERED)
    val events: Flow<RoomsEvent> = _events.receiveAsFlow()

    /** [areaId] is an area id, or UNASSIGNED_AREA for "No room". */
    fun assign(rowId: String, areaId: String) {
        val ids = state.value.rows.firstOrNull { it.id == rowId }?.entityIds ?: return
        viewModelScope.launch {
            if (editor.assignArea(ids, areaId)) savedState[KEY_CHANGED] = true else _events.send(RoomsEvent.SaveFailed)
        }
    }

    fun createRoom(name: String): String? {
        val resolved = AreaNames.resolve(name, source.areas.value) ?: return null
        if (resolved.isNew) viewModelScope.launch { if (!editor.addRoom(resolved.area)) _events.send(RoomsEvent.SaveFailed) }
        return resolved.area.id
    }

    private companion object {
        const val KEY_CHANGED = "rooms.changed"
    }
}
