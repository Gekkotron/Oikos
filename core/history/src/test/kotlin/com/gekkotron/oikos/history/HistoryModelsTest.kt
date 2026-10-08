package com.gekkotron.oikos.history

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryModelsTest {
    @Test
    fun `energy scale follows the unit`() {
        assertEquals(EnergyScale(EnergyKind.COUNTER, 1.0), EnergyScale.of("kWh"))
        assertEquals(EnergyScale(EnergyKind.COUNTER, 0.001), EnergyScale.of("Wh"))
        assertEquals(EnergyScale(EnergyKind.POWER, 1.0), EnergyScale.of("W"))
        assertEquals(EnergyScale(EnergyKind.POWER, 1000.0), EnergyScale.of("kW"))
        assertNull(EnergyScale.of("°C"))
        assertNull(EnergyScale.of(null))
    }
}
