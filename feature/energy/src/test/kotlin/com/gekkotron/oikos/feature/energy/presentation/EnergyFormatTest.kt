package com.gekkotron.oikos.feature.energy.presentation

import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals

class EnergyFormatTest {
    @Test
    fun `power switches to kW at 1000 W with one decimal`() {
        assertEquals("3.2 kW", EnergyFormat.power(3200.0, Locale.US))
        assertEquals("1.0 kW", EnergyFormat.power(1000.0, Locale.US))
        assertEquals("640 W", EnergyFormat.power(640.4, Locale.US))
        assertEquals("0 W", EnergyFormat.power(0.0, Locale.US))
    }

    @Test
    fun `french formatting`() {
        assertEquals("3,2 kW", EnergyFormat.power(3200.0, Locale.FRANCE))
        assertEquals("3,2", EnergyFormat.spokenNumber(3200.0, Locale.FRANCE))
    }

    @Test
    fun `amount keeps the reported unit`() {
        assertEquals("312 L", EnergyFormat.amount(312.0, "L", Locale.US))
        assertEquals("1.5 m³", EnergyFormat.amount(1.46, "m³", Locale.US))
        assertEquals("7", EnergyFormat.amount(7.0, null, Locale.US))
    }
}
