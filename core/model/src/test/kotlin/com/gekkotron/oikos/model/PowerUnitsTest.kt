package com.gekkotron.oikos.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PowerUnitsTest {
    @Test
    fun `power units convert to watts case-insensitively`() {
        assertEquals(1.0, PowerUnits.factor("W"))
        assertEquals(1_000.0, PowerUnits.factor("kW"))
        assertEquals(1_000.0, PowerUnits.factor(" KW "))
        assertEquals(1_000_000.0, PowerUnits.factor("MW"))
    }

    @Test
    fun `other units are not power`() {
        assertNull(PowerUnits.factor(null))
        assertNull(PowerUnits.factor("kWh"))
        assertNull(PowerUnits.factor("°C"))
        assertNull(PowerUnits.factor("VA"))
    }
}
