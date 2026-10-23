package com.gekkotron.oikos.feature.home.rooms

import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Device
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.model.UiOverride
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomsUiBuilderTest {
    private val areas = listOf(Area("kitchen", "Kitchen"), Area("living", "Living room"), Area(UNASSIGNED_AREA, UNASSIGNED_AREA))
    private fun device(id: String, name: String) = Device(id, name, null, null, null)

    @Test
    fun `one row per device, unassigned first, then by name`() {
        val s = RoomsUiBuilder.build(
            entities = listOf(
                testEntity("kettle_power", Kind.SENSOR, deviceId = "kettle", area = "kitchen"),
                testEntity("kettle_switch", Kind.SWITCH, deviceId = "kettle", area = "kitchen"),
                testEntity("plug", Kind.SWITCH, deviceId = "plug"),
                testEntity("aqara", Kind.BINARY_SENSOR, deviceId = "aqara", ui = UiOverride(hidden = true)),
            ).associateBy { it.uniqueId },
            devices = listOf(device("kettle", "Kettle"), device("plug", "Plug"), device("aqara", "Aqara motion")).associateBy { it.id },
            areas = areas,
            locale = Locale.US,
        )
        assertEquals(listOf("aqara", "plug", "kettle"), s.rows.map { it.id })
        assertEquals(listOf("kettle_power", "kettle_switch"), s.rows.last().entityIds)
        assertEquals("kitchen", s.rows.last().areaId)
        assertEquals("Kitchen", s.rows.last().areaName)
        assertEquals(1, s.assigned)
        // "living" has no entity, so the picker drops it.
        assertEquals(listOf("kitchen"), s.rooms.map { it.id })
    }

    @Test
    fun `a device split across rooms is marked as several rooms and counts as assigned`() {
        val s = RoomsUiBuilder.build(
            mapOf(
                "a" to testEntity("a", Kind.SWITCH, deviceId = "d", area = "kitchen"),
                "b" to testEntity("b", Kind.SWITCH, deviceId = "d", area = "living"),
            ),
            mapOf("d" to device("d", "Dual relay")),
            areas, Locale.US,
        )
        assertEquals(null, s.rows.single().areaId)
        assertEquals(1, s.assigned)
    }

    @Test
    fun `entities without a known device get their own row`() {
        val s = RoomsUiBuilder.build(
            mapOf("x" to testEntity("x", Kind.SWITCH, "Loose switch"), "y" to testEntity("y", Kind.SWITCH, deviceId = "gone")),
            emptyMap(), areas, Locale.US,
        )
        assertEquals(setOf("entity:x", "entity:y"), s.rows.map { it.id }.toSet())
        assertTrue(s.rows.any { it.name == "Loose switch" })
    }

    @Test
    fun `no entities is loading`() {
        assertTrue(RoomsUiBuilder.build(emptyMap(), emptyMap(), emptyList(), Locale.US).loading)
    }
}
