package com.gekkotron.oikos.feature.energy.presentation

import com.gekkotron.oikos.feature.energy.energyEntity
import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.Entity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnergyMetersTest {
    private fun power(id: String, name: String, device: String? = "d1", meter: String? = null): Entity =
        energyEntity(id, EnergyRole.CONSUMER, name = name).let { it.copy(deviceId = device, ui = it.ui.copy(energyMeter = meter)) }

    private fun counter(id: String, name: String, device: String? = "d1", unit: String = "kWh"): Entity =
        energyEntity(id, null, unit = unit, name = name).copy(deviceId = device)

    private fun meter(e: Entity, all: List<Entity>) = EnergyMeters.meterFor(e, all)?.uniqueId

    @Test
    fun `an explicit choice wins over automatic pairing`() {
        val p = power("p", "Power A", meter = "other")
        val all = listOf(p, counter("e", "Energy A"), counter("other", "Total", device = "d2"))
        assertEquals("other", meter(p, all))
    }

    @Test
    fun `a dangling explicit choice pairs nothing`() {
        val p = power("p", "Power A", meter = "gone")
        assertNull(meter(p, listOf(p, counter("e", "Energy A"))))
        val notCounter = power("p2", "Power A", meter = "t")
        assertNull(meter(notCounter, listOf(notCounter, counter("t", "Temp", unit = "°C"))))
    }

    @Test
    fun `labels pair power A with energy A among several`() {
        val a = power("pa", "Power A")
        val b = power("pb", "power_b")
        val all = listOf(a, b, counter("ea", "Energy A"), counter("eb", "energy_b"))
        assertEquals("ea", meter(a, all))
        assertEquals("eb", meter(b, all))
    }

    @Test
    fun `unnamed Z2M entities pair by unique id when they share the device name`() {
        // Z2M publishes power_a / energy_a with "name": null, so both are named after the device.
        val a = power("0xa4_power_a_zigbee2mqtt", "powers/ac_water_heater")
        val b = power("0xa4_power_b_zigbee2mqtt", "powers/ac_water_heater")
        val all = listOf(
            a, b,
            counter("0xa4_energy_a_zigbee2mqtt", "powers/ac_water_heater"),
            counter("0xa4_energy_b_zigbee2mqtt", "powers/ac_water_heater"),
            counter("0xa4_energy_produced_a_zigbee2mqtt", "powers/ac_water_heater"),
            counter("0xa4_energy_produced_b_zigbee2mqtt", "powers/ac_water_heater"),
        )
        assertEquals("0xa4_energy_a_zigbee2mqtt", meter(a, all))
        assertEquals("0xa4_energy_b_zigbee2mqtt", meter(b, all))
    }

    @Test
    fun `a grid sensor's export counter is its energy_produced twin`() {
        val grid = energyEntity("0x_power_a_z", EnergyRole.GRID).copy(deviceId = "d1")
        val heater = power("0x_power_b_z", "Heater")
        val all = listOf(grid, heater, counter("0x_energy_a_z", "x"), counter("0x_energy_produced_a_z", "x"), counter("0x_energy_produced_b_z", "x"))
        assertEquals("0x_energy_produced_a_z", EnergyMeters.reverseFor(grid, all)?.uniqueId)
        assertEquals("0x_energy_a_z", meter(grid, all))
        assertNull(EnergyMeters.reverseFor(heater, all))
    }

    @Test
    fun `a solar sensor pairs with its production counter and draws from its energy twin`() {
        val solar = energyEntity("0x_power_b_z", EnergyRole.SOLAR).copy(deviceId = "d1")
        val all = listOf(solar, counter("0x_energy_b_z", "x"), counter("0x_energy_produced_b_z", "x"))
        assertEquals("0x_energy_produced_b_z", meter(solar, all))
        assertEquals("0x_energy_b_z", EnergyMeters.reverseFor(solar, all)?.uniqueId)
        val single = listOf(solar, counter("k", "Total"))
        assertEquals("k", meter(solar, single))
        assertNull(EnergyMeters.reverseFor(solar, single))
    }

    @Test
    fun `a label is preferred to the name`() {
        val p = power("p", "Plug Power").copy(label = "Power")
        val all = listOf(p, counter("e", "Plug Energy").copy(label = "Energy"), counter("x", "Other"))
        assertEquals("e", meter(p, all))
    }

    @Test
    fun `a lone power and a lone counter on a device pair whatever their labels`() {
        val p = power("p", "Puissance")
        assertEquals("e", meter(p, listOf(p, counter("e", "Compteur"))))
    }

    @Test
    fun `a counter on another device is not matched`() {
        val p = power("p", "Power A")
        assertNull(meter(p, listOf(p, counter("e", "Energy A", device = "d2"))))
        val noDevice = power("q", "Power A", device = null)
        assertNull(meter(noDevice, listOf(noDevice, counter("e2", "Energy A", device = null))))
    }

    @Test
    fun `two powers and two counters with unrelated labels pair nothing`() {
        val a = power("pa", "Alpha")
        val b = power("pb", "Beta")
        val all = listOf(a, b, counter("e1", "Total 1"), counter("e2", "Total 2"))
        assertNull(meter(a, all))
        assertNull(meter(b, all))
    }

    @Test
    fun `auto ignores the explicit choice`() {
        val p = power("p", "Power A", meter = "other")
        val all = listOf(p, counter("e", "Energy A"), counter("other", "Total", device = "d2"))
        assertEquals("e", EnergyMeters.auto(p, all)?.uniqueId)
    }

    private fun water(id: String, unit: String, device: String? = "d1", meter: String? = null): Entity =
        energyEntity(id, EnergyRole.WATER, unit = unit).let { it.copy(deviceId = device, ui = it.ui.copy(energyMeter = meter)) }

    private fun waterMeter(e: Entity, all: List<Entity>) = EnergyMeters.waterMeterFor(e, all)?.uniqueId

    @Test
    fun `water auto pairs a meter scale sensor on its device`() {
        listOf("m³", "ft³", "CCF").forEach { unit ->
            val w = water("daily", "L")
            assertEquals("index", waterMeter(w, listOf(w, counter("index", "Index", unit = unit), counter("far", "Far", device = "d2", unit = unit))), unit)
        }
    }

    @Test
    fun `water auto pairs nothing for same-unit pairs, a meter itself or no device`() {
        val gal = water("daily", "gal")
        assertNull(waterMeter(gal, listOf(gal, counter("total", "Total", unit = "gal"))))
        val index = water("index", "m³")
        assertNull(waterMeter(index, listOf(index, counter("other", "Other", unit = "m³"))))
        val loose = water("daily", "L", device = null)
        assertNull(waterMeter(loose, listOf(loose, counter("index", "Index", device = null, unit = "m³"))))
    }

    @Test
    fun `water's explicit choice takes any volume sensor, anywhere, but not itself or a non-volume`() {
        val w = water("daily", "gal", device = null, meter = "utility")
        assertEquals("utility", waterMeter(w, listOf(w, counter("utility", "Utility meter", device = null, unit = "gal"))))
        val self = water("daily", "L", meter = "daily")
        assertNull(waterMeter(self, listOf(self)))
        val kwh = water("daily", "L", meter = "e")
        assertNull(waterMeter(kwh, listOf(kwh, counter("e", "Energy"))))
    }
}
