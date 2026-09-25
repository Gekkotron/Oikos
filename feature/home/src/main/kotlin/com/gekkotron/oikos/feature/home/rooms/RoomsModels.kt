package com.gekkotron.oikos.feature.home.rooms

import com.gekkotron.oikos.model.Area

/**
 * One device (or one entity without a known device).
 * [areaId]: the room its entities share; UNASSIGNED_AREA for none; null when they are split across rooms.
 * [areaName]: the room's display name, or null when there is none to show.
 */
data class RoomRowUi(
    val id: String,
    val name: String,
    val entityIds: List<String>,
    val areaId: String?,
    val areaName: String?,
)

data class RoomsUiState(
    val loading: Boolean = true,
    val rows: List<RoomRowUi> = emptyList(),
    /** Rooms the picker offers: every area except Unassigned. */
    val rooms: List<Area> = emptyList(),
    /** Rows that have a room (split rows count). */
    val assigned: Int = 0,
    /** Something was assigned on this screen: the button says Done rather than Skip. */
    val changed: Boolean = false,
)

sealed interface RoomsEvent {
    data object SaveFailed : RoomsEvent
}
