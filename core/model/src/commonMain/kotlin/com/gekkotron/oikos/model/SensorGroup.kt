package com.gekkotron.oikos.model

/**
 * What a security-relevant binary sensor watches (security spec §3.2). Danger groups come first, and the
 * enum order is the summary order.
 */
enum class SensorGroup(val danger: Boolean) {
    SMOKE(true), CO(true), GAS(true), LEAK(true), SOS(true),
    OPENING(false), MOTION(false), VIBRATION(false), TAMPER(false),
    ;

    companion object {
        /** Null: not shown on the Security tab. */
        fun of(deviceClass: String?): SensorGroup? = when (deviceClass) {
            "door", "window", "garage_door", "opening" -> OPENING
            "motion", "occupancy", "presence" -> MOTION
            "smoke" -> SMOKE
            "carbon_monoxide" -> CO
            "gas" -> GAS
            "moisture" -> LEAK
            "safety" -> SOS
            "vibration" -> VIBRATION
            "tamper" -> TAMPER
            else -> null
        }
    }
}
