package com.gekkotron.oikos.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VolumeUnitsTest {
    @Test
    fun `every HA water unit is a volume`() {
        listOf("L", "mL", "m³", "m3", "ft³", "ft3", "gal", "CCF", " l ").forEach { assertTrue(VolumeUnits.isVolume(it), it) }
        listOf(null, "", "kWh", "W", "L/min", "°C").forEach { assertFalse(VolumeUnits.isVolume(it), it.toString()) }
    }

    @Test
    fun `only m3, ft3 and CCF are meter scale`() {
        listOf("m³", "M3", "ft³", "ccf").forEach { assertTrue(VolumeUnits.isMeterScale(it), it) }
        listOf("L", "mL", "gal", null).forEach { assertFalse(VolumeUnits.isMeterScale(it), it.toString()) }
    }
}
