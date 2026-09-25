package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.model.Area
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

    private suspend fun attempt(write: suspend () -> Unit): Boolean = try {
        write()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
}
