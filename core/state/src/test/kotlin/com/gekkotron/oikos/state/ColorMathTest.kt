package com.gekkotron.oikos.state

import com.gekkotron.oikos.model.Hs
import com.gekkotron.oikos.model.Rgb
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorMathTest {
    private fun hueDistance(a: Double, b: Double) = abs(a - b).let { minOf(it, 360 - it) }

    @Test
    fun `hs to rgb at full value`() {
        assertEquals(Rgb(255, 0, 0), ColorMath.hsToRgb(Hs(0.0, 1.0)))
        assertEquals(Rgb(0, 255, 0), ColorMath.hsToRgb(Hs(120.0, 1.0)))
        assertEquals(Rgb(255, 255, 255), ColorMath.hsToRgb(Hs(200.0, 0.0)))
        assertEquals(Hs(240.0, 1.0), ColorMath.rgbToHs(Rgb(0, 0, 255)))
        assertEquals(Hs(0.0, 0.0), ColorMath.rgbToHs(Rgb(0, 0, 0)))
    }

    @Test
    fun `known xy points`() {
        assertEquals(ColorMath.Xy(0.6401, 0.33), ColorMath.hsToXy(Hs(0.0, 1.0)))
        assertEquals(ColorMath.Xy(0.3127, 0.329), ColorMath.hsToXy(Hs(0.0, 0.0)))
    }

    @Test
    fun `the strip's colour decodes to a blue`() {
        val hs = ColorMath.xyToHs(ColorMath.Xy(0.196, 0.203))
        assertTrue(hueDistance(hs.hue, 208.0) < 1.0, "hue ${hs.hue}")
        assertTrue(abs(hs.saturation - 0.78) < 0.01, "saturation ${hs.saturation}")
    }

    @Test
    fun `hs to xy and back`() {
        for (hue in 0 until 360 step 30) for (saturation in listOf(0.2, 0.5, 1.0)) {
            val back = ColorMath.xyToHs(ColorMath.hsToXy(Hs(hue.toDouble(), saturation)))
            assertTrue(hueDistance(back.hue, hue.toDouble()) < 1.0, "hue $hue/$saturation → ${back.hue}")
            assertTrue(abs(back.saturation - saturation) < 0.01, "saturation $hue/$saturation → ${back.saturation}")
        }
    }

    @Test
    fun `hue never reaches 360`() {
        // Just below red on the magenta side: rounding must wrap to 0, not build Hs(360.0, …).
        val hs = ColorMath.rgbToHs(Rgb(255, 0, 1))
        assertTrue(hs.hue < 360.0)
        val xy = ColorMath.xyToHs(ColorMath.hsToXy(Hs(359.99, 1.0)))
        assertTrue(xy.hue < 360.0)
    }

    @Test
    fun `degenerate xy falls back to white`() {
        assertEquals(Hs(0.0, 0.0), ColorMath.xyToHs(ColorMath.Xy(0.3, 0.0)))
    }

    @Test
    fun `a vanishing y falls back to white instead of NaN`() {
        assertEquals(Hs(0.0, 0.0), ColorMath.xyToHs(ColorMath.Xy(0.3, 1e-320)))
    }
}
