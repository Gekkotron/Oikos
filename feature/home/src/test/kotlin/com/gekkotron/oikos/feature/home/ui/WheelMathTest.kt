package com.gekkotron.oikos.feature.home.ui

import com.gekkotron.oikos.model.Hs
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WheelMathTest {
    private fun near(expected: Double, actual: Double, tolerance: Double = 0.01) =
        assertTrue(abs(expected - actual) < tolerance, "expected $expected, got $actual")

    @Test
    fun `the centre is white and the rim is fully saturated`() {
        assertEquals(Hs(0.0, 0.0), WheelMath.hsAt(50f, 50f, 100f))
        WheelMath.hsAt(50f, 0f, 100f).let { near(0.0, it.hue); near(1.0, it.saturation) }
    }

    @Test
    fun `hue runs clockwise from the top`() {
        near(90.0, WheelMath.hsAt(100f, 50f, 100f).hue)
        near(180.0, WheelMath.hsAt(50f, 100f, 100f).hue)
        near(270.0, WheelMath.hsAt(0f, 50f, 100f).hue)
    }

    @Test
    fun `outside the rim clamps to full saturation and the seam stays below 360`() {
        near(1.0, WheelMath.hsAt(200f, 50f, 100f).saturation)
        assertTrue(WheelMath.hsAt(49.9999f, 0f, 100f).hue < 360.0)
    }

    @Test
    fun `pointOf inverts hsAt`() {
        val (x, y) = WheelMath.pointOf(WheelMath.hsAt(70f, 20f, 100f), 100f)
        near(70.0, x.toDouble(), 0.05)
        near(20.0, y.toDouble(), 0.05)
    }
}
