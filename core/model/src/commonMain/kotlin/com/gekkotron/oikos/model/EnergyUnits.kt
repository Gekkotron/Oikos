package com.gekkotron.oikos.model

/** The energy-counter units the energy screen understands. */
object EnergyUnits {
    /**
     * kWh per one [unit], or null when [unit] is not an energy unit. Wh and kWh ignore case; "MWh" (megawatt-hour)
     * matches exactly, like [PowerUnits]' "MW".
     */
    fun factor(unit: String?): Double? {
        val u = unit?.trim() ?: return null
        return when {
            u == "MWh" -> 1_000.0
            u.equals("wh", ignoreCase = true) -> 0.001
            u.equals("kwh", ignoreCase = true) -> 1.0
            else -> null
        }
    }
}
