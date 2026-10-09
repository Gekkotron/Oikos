package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.source.EntitySource
import kotlinx.coroutines.CancellationException

/**
 * The one feature-side writer of `oikos/ui/…` (spec §4.3), shared by the override sheet and the Rooms
 * step. Every write reports success as a Boolean: offline (PublishFailed, after the source rolled back)
 * and an id that cannot be a topic (IllegalArgumentException) both become false, never a crash.
 */
class OverrideEditor(private val source: EntitySource) {
    suspend fun save(id: String, override: UiOverride): Boolean = attempt { source.setOverride(id, override) }

    /** Moves each entity to [areaId] (an area id, or UNASSIGNED_AREA for "No room"), keeping the rest of its override. */
    suspend fun assignArea(ids: Collection<String>, areaId: String): Boolean {
        for (id in ids) {
            val entity = source.entities.value[id] ?: continue
            if (entity.area == areaId) continue
            if (!save(id, entity.ui.copy(area = areaId))) return false
        }
        return true
    }

    suspend fun addRoom(area: Area): Boolean = attempt { source.setAreas(AreaNames.withArea(source.areas.value, area)) }

    /** Appends every room in [rooms] that is not already declared, in one publish. */
    suspend fun addRooms(rooms: List<Area>): Boolean = attempt {
        source.setAreas(rooms.fold(source.areas.value) { areas, room -> AreaNames.withArea(areas, room) })
    }

    /**
     * The override sheet's save (spec §5.2): [newRooms] the picker created but never published go out
     * first, so the override's area id resolves to a declared room; a failed areas publish leaves the
     * override unsent. A room typed and then left for another one is dropped.
     */
    suspend fun customize(id: String, newRooms: List<Area>, override: UiOverride): Boolean {
        val used = newRooms.filter { it.id == override.area }
        if (used.isNotEmpty() && !addRooms(used)) return false
        return save(id, override)
    }

    /** Room groups spec §6: a custom group name on the room; blank removes it. Publishes the whole areas document. */
    suspend fun setGroupName(areaId: String, kindKey: String, name: String?): Boolean {
        val areas = source.areas.value
        val target = areas.firstOrNull { it.id == areaId && it.id != UNASSIGNED_AREA } ?: return false
        val clean = name?.trim()?.takeIf { it.isNotEmpty() }
        val names = if (clean == null) target.groupNames - kindKey else target.groupNames + (kindKey to clean)
        if (names == target.groupNames) return true
        return attempt { source.setAreas(areas.filter { it.id != UNASSIGNED_AREA }.map { if (it.id == areaId) it.copy(groupNames = names) else it }) }
    }

    /** Takes each device out of its room's group, keeping the rest of its override. */
    suspend fun ungroup(ids: Collection<String>): Boolean {
        for (id in ids) {
            val entity = source.entities.value[id] ?: continue
            if (entity.ui.ungrouped) continue
            if (!save(id, entity.ui.copy(ungrouped = true))) return false
        }
        return true
    }

    private suspend fun attempt(write: suspend () -> Unit): Boolean = try {
        write()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
}
