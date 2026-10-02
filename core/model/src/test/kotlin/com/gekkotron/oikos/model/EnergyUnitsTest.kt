package com.gekkotron.oikos.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnergyUnitsTest {
    @Test
    fun `energy units convert to kWh case-insensitively`() {
        assertEquals(0.001, EnergyUnits.factor("Wh"))
        assertEquals(0.001, EnergyUnits.factor("wh"))
        assertEquals(1.0, EnergyUnits.factor("kWh"))
        assertEquals(1.0, EnergyUnits.factor(" KWH "))
        assertEquals(1_000.0, EnergyUnits.factor("MWh"))
    }

    @Test
    fun `megawatt-hours match exactly`() {
        assertNull(EnergyUnits.factor("mwh"))
    }

    @Test
    fun `other units are not energy`() {
        assertNull(EnergyUnits.factor(null))
        assertNull(EnergyUnits.factor("kW"))
        assertNull(EnergyUnits.factor("W"))
        assertNull(EnergyUnits.factor("°C"))
    }
}
