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
}
