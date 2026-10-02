package com.gekkotron.oikos.feature.security.presentation

import com.gekkotron.oikos.feature.security.alarm
import com.gekkotron.oikos.feature.security.on
import com.gekkotron.oikos.feature.security.panel
import com.gekkotron.oikos.feature.security.sensor
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.SensorGroup
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.transport.LinkState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecurityUiBuilderTest {
    private val areas = listOf(Area("garage", "Garage"), Area("living", "Living room"))

    private fun build(entities: List<Entity>, states: List<EntityState> = emptyList()) =
        SecurityUiBuilder.build(entities.associateBy { it.uniqueId }, states.associateBy { it.uniqueId }, areas, LinkState.Connecting)

    @Test
    fun `only security classes are listed`() {
        val ui = build(listOf(sensor("door", "door"), sensor("fault", null), sensor("problem", "problem"), sensor("smoke", "smoke")))
        assertEquals(setOf("door", "smoke"), ui.sensors.map { it.id }.toSet())
        assertFalse(ui.loading)
    }

    @Test
    fun `security-hidden and folded entities are left out, hidden on Home is not`() {
        val ui = build(
            listOf(
                sensor("a", "door", ui = UiOverride(hidden = true)),
                sensor("b", "door", ui = UiOverride(securityHidden = true)),
                sensor("c", "door", partOf = "cover"),
                panel("p", ui = UiOverride(securityHidden = true)),
                sensor("d", "door"),
            ),
        )
        assertEquals(setOf("a", "d"), ui.sensors.map { it.id }.toSet())
        assertTrue(ui.panels.isEmpty())
    }

    @Test
    fun `active danger, active intrusion, inactive, unavailable - then area and name`() {
        val ui = build(
            listOf(
                sensor("door_living", "door", "Door", area = "living"),
                sensor("door_garage", "door", "Door", area = "garage"),
                sensor("motion", "motion", "Motion", area = "living"),
                sensor("smoke", "smoke", "Smoke", area = "living"),
                sensor("leak", "moisture", "Leak", area = "garage"),
                sensor("window", "window", "Window"),
            ),
            listOf(
                on("door_living", true), on("door_garage", true), on("motion", false), on("smoke", true),
                on("leak", false, available = false), on("window", false),
            ),
        )
        assertEquals(listOf("smoke", "door_garage", "door_living", "motion", "window", "leak"), ui.sensors.map { it.id })
        assertEquals("Garage", ui.sensors[1].areaName)
        assertNull(ui.sensors.single { it.id == "window" }.areaName)
    }

    @Test
    fun `summary counts active available sensors by group in enum order`() {
        val ui = build(
            listOf(sensor("d1", "door"), sensor("d2", "window"), sensor("m", "occupancy"), sensor("s", "smoke"), sensor("x", "door")),
            listOf(on("d1", true), on("d2", true), on("m", true), on("s", true), on("x", true, available = false)),
        )
        assertEquals(listOf(GroupCount(SensorGroup.SMOKE, 1), GroupCount(SensorGroup.OPENING, 2), GroupCount(SensorGroup.MOTION, 1)), ui.summary)
    }

    @Test
    fun `unknown sensors sort with inactive and are not counted`() {
        val ui = build(
            listOf(sensor("a", "door", "A"), sensor("b", "door", "B"), sensor("c", "door", "C")),
            listOf(on("b", true), EntityState("c", StateValue.Unknown)),
        )
        assertEquals(listOf("b", "a", "c"), ui.sensors.map { it.id })
        assertNull(ui.sensors.single { it.id == "c" }.active)
        assertNull(ui.sensors.single { it.id == "a" }.active)
        assertEquals(listOf(GroupCount(SensorGroup.OPENING, 1)), ui.summary)
    }

    @Test
    fun `panels carry state, capabilities and user order`() {
        val ui = build(
            listOf(panel("b", "B", order = 1), panel("a", "A")),
            listOf(alarm("b", AlarmState.ARMED_AWAY, stale = true)),
        )
        assertEquals(listOf("b", "a"), ui.panels.map { it.id })
        assertEquals(AlarmState.ARMED_AWAY, ui.panels[0].state)
        assertTrue(ui.panels[0].stale)
        assertNull(ui.panels[1].state)
        assertTrue(ui.panels[1].capabilities.codeDisarmRequired)
    }

    @Test
    fun `stale is carried onto sensor rows and panels, and does not change sorting or counting`() {
        val ui = build(
            listOf(panel(), sensor("a", "door"), sensor("b", "door")),
            listOf(alarm("alarm", AlarmState.DISARMED, stale = true), on("a", true, stale = true), on("b", false)),
        )
        assertTrue(ui.panels[0].stale)
        assertEquals(listOf("a", "b"), ui.sensors.map { it.id })
        assertTrue(ui.sensors[0].stale)
        assertFalse(ui.sensors[1].stale)
        assertEquals(listOf(GroupCount(SensorGroup.OPENING, 1)), ui.summary)
    }

    @Test
    fun `nothing yields empty lists`() {
        val ui = build(listOf(sensor("lamp", null).copy(kind = Kind.LIGHT)))
        assertTrue(ui.panels.isEmpty() && ui.sensors.isEmpty() && ui.summary.isEmpty())
    }

    @Test
    fun `sensors fall into sections, openings first, empty sections left out`() {
        val ui = build(
            listOf(sensor("door", "door"), sensor("smoke", "smoke"), sensor("co", "carbon_monoxide"), sensor("pool", "moisture"), sensor("tamper", "tamper")),
            listOf(on("door", false), on("smoke", false), on("co", true)),
        )
        assertEquals(
            listOf(SensorSection.OPENINGS, SensorSection.FIRE, SensorSection.WATER, SensorSection.OTHER),
            ui.sections.map { it.section },
        )
        // Within a section the tab order holds: the active CO alarm comes before the calm smoke one.
        assertEquals(listOf("co", "smoke"), ui.sections.first { it.section == SensorSection.FIRE }.rows.map { it.id })
        assertEquals(ui.sensors.toSet(), ui.sections.flatMap { it.rows }.toSet())
    }
}
