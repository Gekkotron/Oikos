package com.gekkotron.oikos.feature.home.rooms

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Device
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import java.util.Locale

/** Entities + devices + areas → one row per device, the ones without a room first (spec §5.2 step 5). */
object RoomsUiBuilder {
    private const val ENTITY_ROW = "entity:"

    fun build(entities: Map<String, Entity>, devices: Map<String, Device>, areas: List<Area>, locale: Locale): RoomsUiState {
        val names = areas.associate { it.id to it.name }
        val rows = entities.values
            .groupBy { e -> e.deviceId?.takeIf { it in devices } ?: (ENTITY_ROW + e.uniqueId) }
            .map { (rowId, members) ->
                val sorted = members.sortedBy { it.uniqueId }
                val rooms = sorted.mapTo(linkedSetOf()) { it.area }
                val areaId = rooms.singleOrNull()
                RoomRowUi(
                    id = rowId,
                    name = devices[rowId]?.name ?: sorted.first().name,
                    entityIds = sorted.map { it.uniqueId },
                    areaId = areaId,
                    areaName = areaId?.takeIf { it != UNASSIGNED_AREA }?.let { names[it] ?: it },
                )
            }
            .sortedWith(compareBy<RoomRowUi>({ it.areaId != UNASSIGNED_AREA }, { it.name.lowercase(locale) }, { it.id }))
        val occupied = entities.values.mapTo(mutableSetOf()) { it.area }
        return RoomsUiState(
            loading = entities.isEmpty(),
            rows = rows,
            rooms = areas.filter { it.id != UNASSIGNED_AREA && it.id in occupied },
            assigned = rows.count { it.areaId != UNASSIGNED_AREA },
        )
    }
}
