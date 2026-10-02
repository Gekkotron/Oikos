package com.gekkotron.oikos.feature.home.ui

import com.gekkotron.oikos.model.Hs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Hue wheel geometry (LED spec §4.3): hue 0° at the top, clockwise; saturation = distance from the centre / radius. */
internal object WheelMath {
    fun hsAt(x: Float, y: Float, diameter: Float): Hs {
        val r = diameter / 2.0
        val dx = x - r
        val dy = y - r
        val distance = hypot(dx, dy)
        if (r <= 0.0 || distance == 0.0) return Hs(0.0, 0.0)
        var hue = Math.toDegrees(atan2(dx, -dy))
        if (hue < 0) hue += 360.0
        if (hue >= 360.0) hue -= 360.0
        return Hs(hue, (distance / r).coerceAtMost(1.0))
    }

    fun pointOf(hs: Hs, diameter: Float): Pair<Float, Float> {
        val r = diameter / 2.0
        val a = Math.toRadians(hs.hue)
        return Pair((r + hs.saturation * r * sin(a)).toFloat(), (r - hs.saturation * r * cos(a)).toFloat())
    }
}
