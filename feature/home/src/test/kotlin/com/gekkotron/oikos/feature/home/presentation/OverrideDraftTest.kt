package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.transport.LinkState
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverrideDraftTest {
    private fun card(entity: Entity, labels: LabelText = LabelText { null }): CardUi =
        HomeUiBuilder.build(mapOf(entity.uniqueId to entity), emptyMap(), listOf(Area("kitchen", "Kitchen")), RoomFilter.All, LinkState.Connecting, Locale.US, labels)
            .card(entity.uniqueId)!!

    // Area comes from suggested_area: no explicit override.
    private val suggested = card(testEntity("lamp", Kind.LIGHT, "Lamp", area = "kitchen"))

    @Test
    fun `untouched fields keep the original override, so a suggested area is not frozen`() {
        assertEquals(UiOverride(), OverrideDraft.from(suggested).applyTo(suggested))
    }

    @Test
    fun `changed fields are written`() {
        val draft = OverrideDraft.from(suggested).copy(name = " Ceiling ", areaId = "living", favorite = true, hidden = true, order = "3")
        assertEquals(UiOverride(name = "Ceiling", area = "living", favorite = true, hidden = true, order = 3), draft.applyTo(suggested))
    }

    @Test
    fun `saving a translated name unchanged keeps it translated`() {
        val probe = testEntity("t", Kind.SENSOR, "Probe Temperature").copy(deviceName = "Probe", label = "Temperature")
        val translated = card(probe) { "Température" }
        assertEquals("Probe Température", translated.name)
        assertEquals(UiOverride(), OverrideDraft.from(translated).applyTo(translated))
    }

    @Test
    fun `a blank name falls back to the discovered name`() {
        val renamed = card(testEntity("lamp", Kind.LIGHT, "Ceiling", ui = UiOverride(name = "Ceiling")))
        assertEquals(UiOverride(), OverrideDraft.from(renamed).copy(name = "  ").applyTo(renamed))
    }

    @Test
    fun `a blank position means automatic`() {
        val ordered = card(testEntity("lamp", Kind.LIGHT, ui = UiOverride(order = 4)))
        assertEquals("4", OverrideDraft.from(ordered).order)
        assertEquals(UiOverride(), OverrideDraft.from(ordered).copy(order = "").applyTo(ordered))
    }

    @Test
    fun `position must be a whole number from 0 to 9999`() {
        val d = OverrideDraft.from(suggested)
        assertTrue(d.copy(order = "").orderValid)
        assertTrue(d.copy(order = " 12 ").orderValid)
        assertFalse(d.copy(order = "-1").orderValid)
        assertFalse(d.copy(order = "10000").orderValid)
        assertFalse(d.copy(order = "1.5").orderValid)
    }

    @Test
    fun `sensor style and energy role are written, and other kinds keep theirs`() {
        val sensor = card(testEntity("power", Kind.SENSOR, deviceClass = "power"))
        assertEquals(
            UiOverride(cardStyle = HomeUiBuilder.HERO_WEATHER, energyRole = EnergyRole.GRID),
            OverrideDraft.from(sensor).copy(cardStyle = HomeUiBuilder.HERO_WEATHER, energyRole = EnergyRole.GRID).applyTo(sensor),
        )
        val styled = card(testEntity("sw", Kind.SWITCH, ui = UiOverride(cardStyle = "future_style")))
        assertEquals(UiOverride(cardStyle = "future_style", favorite = true), OverrideDraft.from(styled).copy(favorite = true).applyTo(styled))
    }
}
