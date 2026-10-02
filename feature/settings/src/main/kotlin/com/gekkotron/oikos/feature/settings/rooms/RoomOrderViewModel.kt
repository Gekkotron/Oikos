package com.gekkotron.oikos.feature.settings.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.transport.PublishFailed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RoomOrderUi(val rooms: List<Area> = emptyList(), val hasUnassigned: Boolean = false)

sealed interface RoomOrderEvent {
    data object SaveFailed : RoomOrderEvent
}

@HiltViewModel
class RoomOrderViewModel @Inject constructor(private val source: EntitySource) : ViewModel() {
    val state: StateFlow<RoomOrderUi> = source.areas
        .map(::toUi)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), toUi(source.areas.value))

    private val _events = Channel<RoomOrderEvent>(Channel.BUFFERED)
    val events: Flow<RoomOrderEvent> = _events.receiveAsFlow()

    /** The list as the user dropped it; publishes once, and only if it changed. */
    fun commit(order: List<String>) {
        if (order == state.value.rooms.map { it.id }) return
        viewModelScope.launch {
            try {
                source.setAreas(RoomOrder.apply(source.areas.value, order))
            } catch (e: PublishFailed) {
                _events.send(RoomOrderEvent.SaveFailed)
            }
        }
    }

    /** TalkBack's Move up (-1) / Move down (+1). */
    fun move(id: String, delta: Int) {
        val ids = state.value.rooms.map { it.id }.toMutableList()
        val from = ids.indexOf(id)
        val to = from + delta
        if (from < 0 || to !in ids.indices) return
        ids.add(to, ids.removeAt(from))
        commit(ids)
    }

    private fun toUi(areas: List<Area>) = RoomOrderUi(areas.filter { it.id != UNASSIGNED_AREA }, areas.any { it.id == UNASSIGNED_AREA })
}
