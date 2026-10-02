package com.gekkotron.oikos.model

/** The power units the energy screen understands (energy spec §2). */
object PowerUnits {
    /** Watts per one [unit], or null when [unit] is not a power unit. */
    fun factor(unit: String?): Double? = when (unit?.trim()?.lowercase()) {
        "w" -> 1.0
        "kw" -> 1_000.0
        "mw" -> 1_000_000.0
        else -> null
    }
}
