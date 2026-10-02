package com.gekkotron.oikos.state

import com.gekkotron.oikos.model.Hs
import com.gekkotron.oikos.model.Rgb
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Light colour conversions (LED control spec §3.2). Colours carry no brightness: hs ↔ rgb works at full
 * value, xy ↔ rgb at Y = 1 normalised to the brightest channel. sRGB primaries, D65 white.
 */
object ColorMath {
    data class Xy(val x: Double, val y: Double)

    val WHITE = Xy(0.3127, 0.329)

    fun hsToRgb(hs: Hs): Rgb {
        val (r, g, b) = hsToUnit(hs)
        return Rgb(byte(r), byte(g), byte(b))
    }

    fun rgbToHs(rgb: Rgb): Hs = unitToHs(rgb.r / 255.0, rgb.g / 255.0, rgb.b / 255.0)

    fun hsToXy(hs: Hs): Xy {
        val (r, g, b) = hsToUnit(hs).map(::linear)
        val x = 0.4124 * r + 0.3576 * g + 0.1805 * b
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = 0.0193 * r + 0.1192 * g + 0.9505 * b
        val sum = x + y + z
        if (sum <= 0.0) return WHITE
        return Xy(round4(x / sum), round4(y / sum))
    }

    fun xyToHs(xy: Xy): Hs {
        if (xy.y <= 0.0) return Hs(0.0, 0.0)
        val bigX = xy.x / xy.y
        val bigZ = (1 - xy.x - xy.y) / xy.y
        val r = (3.2406 * bigX - 1.5372 - 0.4986 * bigZ).coerceAtLeast(0.0)
        val g = (-0.9689 * bigX + 1.8758 + 0.0415 * bigZ).coerceAtLeast(0.0)
        val b = (0.0557 * bigX - 0.2040 + 1.0570 * bigZ).coerceAtLeast(0.0)
        val max = maxOf(r, g, b)
        if (max <= 0.0) return Hs(0.0, 0.0)
        return unitToHs(gamma(r / max), gamma(g / max), gamma(b / max))
    }

    /** Gamma-encoded sRGB channels 0..1 at full value. */
    private fun hsToUnit(hs: Hs): List<Double> {
        val c = hs.saturation
        val h = hs.hue / 60.0
        val x = c * (1 - abs(h % 2 - 1))
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(c, x, 0.0)
            1 -> Triple(x, c, 0.0)
            2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c)
            4 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = 1 - c
        return listOf(r + m, g + m, b + m)
    }

    private fun unitToHs(r: Double, g: Double, b: Double): Hs {
        val max = maxOf(r, g, b)
        val d = max - minOf(r, g, b)
        if (max <= 0.0 || d <= 0.0) return Hs(0.0, 0.0)
        val h = when (max) {
            r -> 60 * (((g - b) / d) % 6)
            g -> 60 * ((b - r) / d + 2)
            else -> 60 * ((r - g) / d + 4)
        }
        val hue = ((h % 360) + 360) % 360
        return Hs(if (hue >= 360.0) 0.0 else hue, (d / max).coerceIn(0.0, 1.0))
    }

    private fun linear(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun gamma(c: Double) = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055

    private fun byte(c: Double) = (c * 255).roundToInt().coerceIn(0, 255)

    private fun round4(v: Double) = (v * 10_000).roundToInt() / 10_000.0
}
