package com.gekkotron.oikos.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SensorGroupTest {
    @Test
    fun `device classes map to their group`() {
        for (c in listOf("door", "window", "garage_door", "opening")) assertEquals(SensorGroup.OPENING, SensorGroup.of(c))
        for (c in listOf("motion", "occupancy", "presence")) assertEquals(SensorGroup.MOTION, SensorGroup.of(c))
        assertEquals(SensorGroup.SMOKE, SensorGroup.of("smoke"))
        assertEquals(SensorGroup.CO, SensorGroup.of("carbon_monoxide"))
        assertEquals(SensorGroup.GAS, SensorGroup.of("gas"))
        assertEquals(SensorGroup.LEAK, SensorGroup.of("moisture"))
        assertEquals(SensorGroup.SOS, SensorGroup.of("safety"))
        assertEquals(SensorGroup.VIBRATION, SensorGroup.of("vibration"))
        assertEquals(SensorGroup.TAMPER, SensorGroup.of("tamper"))
    }

    @Test
    fun `other classes are not security sensors`() {
        assertNull(SensorGroup.of(null))
        assertNull(SensorGroup.of("problem"))
        assertNull(SensorGroup.of("battery"))
    }

    @Test
    fun `danger groups come first`() {
        assertTrue(SensorGroup.entries.takeWhile { it.danger }.size == 5)
        assertFalse(SensorGroup.OPENING.danger)
        assertTrue(SensorGroup.LEAK.danger)
    }
}
