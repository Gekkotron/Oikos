package com.gekkotron.oikos.history

import com.gekkotron.oikos.model.Bindings
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private object NoBindings : Bindings

class HistoryRefsTest {
    private fun entity(kind: Kind, unit: String?, id: String = "e") = Entity(
        uniqueId = id, deviceId = null, name = "E", kind = kind, deviceClass = null, unit = unit, icon = null,
        capabilities = Capabilities(), bindings = NoBindings,
    )

    @Test
    fun `a numeric sensor keys history by its uniqueId`() {
        assertEquals(HistoryRef("living_room_temperature"), HistoryRefs.of(entity(Kind.SENSOR, "°C", "living_room_temperature")))
    }

    @Test
    fun `a sensor without a unit, or a kind that is not charted, has none`() {
        assertNull(HistoryRefs.of(entity(Kind.SENSOR, null)))
        assertNull(HistoryRefs.of(entity(Kind.SENSOR, "   ")))
        assertNull(HistoryRefs.of(entity(Kind.SWITCH, "W")))
        assertNull(HistoryRefs.of(entity(Kind.LIGHT, null)))
    }

    @Test
    fun `a climate keys history by its uniqueId regardless of unit`() {
        assertEquals(HistoryRef("remi_climatisation"), HistoryRefs.of(entity(Kind.CLIMATE, null, "remi_climatisation")))
    }

    @Test
    fun `a binary sensor keys both chart and change by its uniqueId`() {
        val door = entity(Kind.BINARY_SENSOR, null, "entrance_door")
        assertEquals(HistoryRef("entrance_door"), HistoryRefs.of(door))
        assertEquals(HistoryRef("entrance_door"), HistoryRefs.changeRef(door))
    }

    @Test
    fun `changeRef is null for everything but a binary sensor`() {
        assertNull(HistoryRefs.changeRef(entity(Kind.SENSOR, "°C")))
        assertNull(HistoryRefs.changeRef(entity(Kind.CLIMATE, null)))
        assertNull(HistoryRefs.changeRef(entity(Kind.SWITCH, null)))
    }
}
