package com.gekkotron.oikos.feature.security.presentation

import com.gekkotron.oikos.feature.security.action
import com.gekkotron.oikos.feature.security.alarm
import com.gekkotron.oikos.feature.security.on
import com.gekkotron.oikos.feature.security.panel
import com.gekkotron.oikos.feature.security.sensor
import com.gekkotron.oikos.history.LastChange
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.SensorGroup
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.SwitchType
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.transport.LinkState
import java.time.Duration
import java.time.Instant
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
    fun `stable order - area then name, unassigned last - state flips do not reorder`() {
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
        assertEquals(listOf("door_garage", "leak", "door_living", "motion", "smoke", "window"), ui.sensors.map { it.id })
        assertEquals("Garage", ui.sensors[0].areaName)
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
    fun `unknown sensors keep their alphabetical slot and are not counted`() {
        val ui = build(
            listOf(sensor("a", "door", "A"), sensor("b", "door", "B"), sensor("c", "door", "C")),
            listOf(on("b", true), EntityState("c", StateValue.Unknown)),
        )
        assertEquals(listOf("a", "b", "c"), ui.sensors.map { it.id })
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
        // Stable order within a section: alphabetical by name; state flips do not reorder.
        assertEquals(listOf("co", "smoke"), ui.sections.first { it.section == SensorSection.FIRE }.rows.map { it.id })
        assertEquals(ui.sensors.toSet(), ui.sections.flatMap { it.rows }.toSet())
    }

    @Test
    fun `opted-in switches and buttons are actions, sorted by area then name`() {
        val ui = build(
            listOf(
                action("siren", name = "Siren", area = "living"),
                action("flood", name = "Floodlight", area = "garage"),
                action("panic", Kind.BUTTON, name = "Panic"),
                action("coffee", name = "Coffee", ui = UiOverride()),
                action("hidden_sw", name = "Hidden", ui = UiOverride(securityAction = true, hidden = true)),
            ),
            listOf(on("siren", true), on("flood", false, available = false)),
        )
        assertEquals(listOf("flood", "siren", "hidden_sw", "panic"), ui.actions.map { it.id })
        val siren = ui.action("siren")!!
        assertEquals(
            ActionRow(
                "siren", "Siren", "Living room", Kind.SWITCH,
                on = true, available = true, stale = false, ask = true, switchType = SwitchType.SWITCH,
            ),
            siren,
        )
        assertNull(ui.action("panic")!!.on)
        assertFalse(ui.action("flood")!!.available)
        assertTrue(ui.sensors.isEmpty())
    }

    @Test
    fun `ask follows the override and an action alone is not empty`() {
        val ui = build(listOf(action("panic", Kind.BUTTON, ui = UiOverride(securityAction = true, securityAsk = false))))
        assertFalse(ui.action("panic")!!.ask)
        assertFalse(ui.isEmpty)
        assertTrue(build(emptyList()).isEmpty)
    }

    /** Device sheet spec §5.3: a door opener always sends Open; asks when securityAsk is on. */
    @Test
    fun `door action tap is always open`() {
        val row = ActionRow(
            "d", "Gate", "Garage", Kind.SWITCH,
            on = true, available = true, stale = false, ask = true, switchType = SwitchType.DOOR,
        )
        assertEquals(ActionTap(Command.TurnOn(), confirm = true), row.tap())
        assertEquals(ActionTap(Command.TurnOn(), confirm = false), row.copy(ask = false).tap())
    }

    @Test
    fun `siren action tap toggles`() {
        val on = ActionRow(
            "s", "Siren", null, Kind.SWITCH,
            on = true, available = true, stale = false, ask = true, switchType = SwitchType.SIREN,
        )
        assertEquals(ActionTap(Command.TurnOff, confirm = false), on.tap())
        val off = on.copy(on = false)
        assertEquals(ActionTap(Command.TurnOn(), confirm = true), off.tap())
        assertEquals(ActionTap(Command.TurnOn(), confirm = false), off.copy(ask = false).tap())
    }

    @Test
    fun `chime action always plays and never asks`() {
        val row = ActionRow(
            "c", "Chime", null, Kind.SWITCH,
            on = null, available = true, stale = false, ask = true, switchType = SwitchType.CHIME,
        )
        assertEquals(ActionTap(Command.TurnOn(), confirm = false), row.tap())
    }

    @Test
    fun `plain switch keeps today's toggle`() {
        val row = ActionRow(
            "p", "Plug", null, Kind.SWITCH,
            on = true, available = true, stale = false, ask = true, switchType = SwitchType.SWITCH,
        )
        assertEquals(ActionTap(Command.TurnOff, confirm = false), row.tap())
        assertEquals(ActionTap(Command.TurnOn(), confirm = true), row.copy(on = false).tap())
    }

    /** Review focus: a siren that flips on between tap and dialog click must still send the command the user agreed to. */
    @Test
    fun `pending action captures the command at tap time`() {
        val sirenOff = ActionRow(
            "s", "Siren", null, Kind.SWITCH,
            on = false, available = true, stale = false, ask = true, switchType = SwitchType.SIREN,
        )
        val pending = pendingAction(sirenOff)!!
        assertEquals(Command.TurnOn(), pending.command)
        // The row's live state echo later says the siren is on — the captured command stays TurnOn.
        assertEquals(pending.command, sirenOff.copy(on = true).let { pending.command })
    }

    @Test
    fun `pending action is null when no confirm is needed`() {
        val chime = ActionRow(
            "c", "Chime", null, Kind.SWITCH,
            on = null, available = true, stale = false, ask = true, switchType = SwitchType.CHIME,
        )
        assertNull(pendingAction(chime))
        val sirenOn = ActionRow(
            "s", "Siren", null, Kind.SWITCH,
            on = true, available = true, stale = false, ask = true, switchType = SwitchType.SIREN,
        )
        // Siren going off never asks.
        assertNull(pendingAction(sirenOn))
    }

    @Test
    fun `builder reads showAs onto the row`() {
        val ui = build(
            listOf(
                action("gate", name = "Gate", ui = UiOverride(securityAction = true, showAs = "door")),
                action("chime", name = "Chime", ui = UiOverride(securityAction = true, showAs = "chime")),
            ),
        )
        assertEquals(SwitchType.DOOR, ui.action("gate")!!.switchType)
        assertEquals(SwitchType.CHIME, ui.action("chime")!!.switchType)
    }

    private val t0 = Instant.parse("2026-10-14T06:00:00Z")
    private val month = Duration.ofDays(30)

    private fun since(server: LastChange?, local: Instant?): Since? {
        val state = EntityState("door", StateValue.OnOff(false), changedAt = local)
        val changes = server?.let { mapOf("door" to it) } ?: emptyMap()
        return SecurityUiBuilder.build(mapOf("door" to sensor("door", "door")), mapOf("door" to state), areas, LinkState.Connecting, changes)
            .sensors.single().since
    }

    @Test
    fun `since merges the server answer with a live flip`() {
        assertEquals(Since.At(t0), since(LastChange.At(t0), null))
        assertEquals(Since.At(t0.plusSeconds(60)), since(LastChange.At(t0), t0.plusSeconds(60)))
        assertEquals(Since.At(t0), since(LastChange.At(t0), t0.minusSeconds(60)))
        assertEquals(Since.Over(month), since(LastChange.OlderThan(month), null))
        assertEquals(Since.At(t0), since(LastChange.OlderThan(month), t0))
        assertEquals(Since.At(t0), since(LastChange.NotRecorded, t0))
        assertEquals(Since.At(t0), since(null, t0))
        assertNull(since(LastChange.NotRecorded, null))
        assertNull(since(null, null))
    }
}
