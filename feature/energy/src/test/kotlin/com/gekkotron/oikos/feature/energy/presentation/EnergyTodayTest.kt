package com.gekkotron.oikos.feature.energy.presentation

import com.gekkotron.oikos.feature.energy.energyEntity
import com.gekkotron.oikos.feature.energy.recorded
import com.gekkotron.oikos.history.EnergyKind
import com.gekkotron.oikos.history.HistoryRef
import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.Entity
import org.junit.Test
import kotlin.test.assertEquals

class EnergyTodayTest {
    private fun power(id: String, role: EnergyRole, device: String): Entity =
        energyEntity(id, role).copy(deviceId = device).recorded("power")

    private fun counter(id: String, device: String, unit: String = "kWh"): Entity =
        energyEntity(id, null, unit = unit).copy(deviceId = device).recorded("energy")

    private fun requests(vararg entities: Entity) = EnergyToday.requests(entities.associateBy { it.uniqueId })

    @Test
    fun `a power entity with a paired meter asks for the meter's counter`() {
        val r = requests(power("heater_power", EnergyRole.CONSUMER, "d"), counter("heater_energy", "d"))
        assertEquals(setOf("heater_energy"), r.keys)
        val (ref, scale) = r.getValue("heater_energy")
        assertEquals(HistoryRef("heater_energy"), ref)
        assertEquals(EnergyKind.COUNTER, scale.kind)
    }

    @Test
    fun `a power entity without a meter asks for its own integral`() {
        val r = requests(power("heater", EnergyRole.CONSUMER, "d"))
        assertEquals(setOf("heater"), r.keys)
        assertEquals(EnergyKind.POWER, r.getValue("heater").second.kind)
    }

    @Test
    fun `an entity without a unit asks nothing`() {
        assertEquals(emptyMap(), requests(energyEntity("heater", EnergyRole.CONSUMER, unit = null)))
    }

    @Test
    fun `a derived house also asks for its export counters`() {
        val r = requests(
            power("grid_power", EnergyRole.GRID, "g"), counter("grid_energy", "g"), counter("grid_energy_produced", "g"),
            power("solar_power", EnergyRole.SOLAR, "s"), counter("solar_energy", "s"),
        )
        assertEquals(setOf("grid_energy", "grid_energy_produced", "solar_energy"), r.keys)
    }

    @Test
    fun `a signed role without a meter asks nothing`() {
        assertEquals(emptyMap(), requests(power("battery", EnergyRole.BATTERY, "b")))
        assertEquals(emptyMap(), requests(power("grid", EnergyRole.GRID, "g")))
    }

}
