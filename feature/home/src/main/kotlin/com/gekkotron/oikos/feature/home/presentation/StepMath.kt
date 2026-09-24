package com.gekkotron.oikos.feature.home.presentation

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToLong

object StepMath {
    /** [target] + [delta], clamped to [min]..[max], rounded to 2 decimals so 0.1 steps never drift. */
    fun stepTemperature(target: Double, delta: Double, min: Double, max: Double): Double =
        round2((target + delta).coerceIn(min, max))

    /** Nearest value on the grid min, min + step, … */
    fun snap(value: Double, min: Double, step: Double): Double =
        if (step <= 0) value else round2(min + ((value - min) / step).roundToLong() * step)

    private fun round2(v: Double) = BigDecimal(v).setScale(2, RoundingMode.HALF_UP).toDouble()
}
