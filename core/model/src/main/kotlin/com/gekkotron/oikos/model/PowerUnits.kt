package com.gekkotron.oikos.model

/** The power units the energy screen understands (energy spec §2). */
object PowerUnits {
    /**
     * Watts per one [unit], or null when [unit] is not a power unit. W and kW ignore case; "mW" (milliwatt,
     * an HA unit) and "MW" (megawatt) differ only by case, so they match exactly and "mw" matches neither.
     */
    fun factor(unit: String?): Double? {
        val u = unit?.trim() ?: return null
        return when {
            u == "mW" -> 0.001
            u == "MW" -> 1_000_000.0
            u.equals("w", ignoreCase = true) -> 1.0
            u.equals("kw", ignoreCase = true) -> 1_000.0
            else -> null
        }
    }
}
