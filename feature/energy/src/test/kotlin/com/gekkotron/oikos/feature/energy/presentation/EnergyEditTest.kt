package com.gekkotron.oikos.feature.energy.presentation

import com.gekkotron.oikos.feature.energy.energyEntity
import com.gekkotron.oikos.model.ConsumerIcon
import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.UiOverride
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnergyEditTest {
    /** A consumer the user already placed in a room and starred on Home. */
    private val heater = energyEntity("heater", EnergyRole.CONSUMER, name = "PowerB").let {
        it.copy(ui = it.ui.copy(area = "garage", favorite = true, order = 2))
    }

    @Test
    fun `rename keeps every other override field`() {
        val o = EnergyEdit.apply(heater, SensorEdit("Water heater", EnergyRole.CONSUMER, invert = false))
        assertEquals(UiOverride(name = "Water heater", area = "garage", favorite = true, order = 2, energyRole = EnergyRole.CONSUMER), o)
    }

    @Test
    fun `blank name resets to the discovered name`() {
        val renamed = heater.copy(name = "Water heater", ui = heater.ui.copy(name = "Water heater"))
        assertNull(EnergyEdit.apply(renamed, SensorEdit("  ", EnergyRole.CONSUMER, invert = false)).name)
    }

    @Test
    fun `an unchanged name keeps the stored one`() {
        // No typed name yet: saving the shown (discovered) name must not pin it as an override.
        assertNull(EnergyEdit.apply(heater, SensorEdit("PowerB", EnergyRole.CONSUMER, invert = false)).name)
    }

    @Test
    fun `invert is kept for grid and dropped for solar`() {
        assertEquals(true, EnergyEdit.apply(heater, SensorEdit("PowerB", EnergyRole.GRID, invert = true)).energyInvert)
        assertEquals(false, EnergyEdit.apply(heater, SensorEdit("PowerB", EnergyRole.SOLAR, invert = true)).energyInvert)
    }

    @Test
    fun `role none removes it from energy`() {
        assertNull(EnergyEdit.apply(heater, SensorEdit("PowerB", null, invert = false)).energyRole)
    }

    @Test
    fun `the edit starts from the entity`() {
        assertEquals(SensorEdit("PowerB", EnergyRole.CONSUMER, invert = false), EnergyEdit.from(heater))
    }

    @Test
    fun `an icon is stored on a consumer and keeps the other fields`() {
        val o = EnergyEdit.apply(heater, SensorEdit("PowerB", EnergyRole.CONSUMER, invert = false, icon = ConsumerIcon.OVEN))
        assertEquals(UiOverride(area = "garage", favorite = true, order = 2, energyRole = EnergyRole.CONSUMER, energyIcon = "oven"), o)
    }

    @Test
    fun `the icon is cleared when the role is no longer consumer`() {
        val withIcon = heater.copy(ui = heater.ui.copy(energyIcon = "oven"))
        assertEquals(ConsumerIcon.OVEN, EnergyEdit.from(withIcon).icon)
        assertNull(EnergyEdit.apply(withIcon, SensorEdit("PowerB", EnergyRole.SOLAR, invert = false, icon = ConsumerIcon.OVEN)).energyIcon)
        assertNull(EnergyEdit.apply(withIcon, SensorEdit("PowerB", null, invert = false, icon = ConsumerIcon.OVEN)).energyIcon)
    }

    @Test
    fun `no icon clears a stored one`() {
        val withIcon = heater.copy(ui = heater.ui.copy(energyIcon = "oven"))
        assertNull(EnergyEdit.apply(withIcon, SensorEdit("PowerB", EnergyRole.CONSUMER, invert = false)).energyIcon)
    }
}
