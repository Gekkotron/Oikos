package com.gekkotron.oikos.feature.settings.rooms

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.UNASSIGNED_AREA

/** Builds the areas document for a new chip order (settings spec §4.1). */
object RoomOrder {
    /**
     * [areas] is `EntitySource.areas` (the computed list); [newOrder] the room ids as the user left them.
     * Ids no longer in [areas] are skipped, rooms of [areas] missing from [newOrder] (they appeared during the
     * drag) follow in their current order, and Unassigned is never written.
     */
    fun apply(areas: List<Area>, newOrder: List<String>): List<Area> {
        val rooms = areas.filter { it.id != UNASSIGNED_AREA }
        val byId = rooms.associateBy { it.id }
        val ordered = newOrder.distinct().mapNotNull(byId::get)
        val rest = rooms.filter { it !in ordered }
        return (ordered + rest).mapIndexed { index, area -> area.copy(order = index) }
    }
}
