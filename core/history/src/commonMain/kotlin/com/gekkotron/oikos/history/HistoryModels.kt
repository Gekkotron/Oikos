package com.gekkotron.oikos.history

/** Where an entity was recorded: the MQTT topic and the JSON key inside its payload. */
data class HistoryRef(val topic: String, val key: String)

/** A window of history; [ttlMillis] is how long a cached answer stays fresh. */
enum class HistoryRange(val query: String, val ttlMillis: Long) {
    H24("24h", 60_000),
    D7("7d", 600_000),
    D30("30d", 600_000),
}

enum class EnergyKind(val query: String) {
    COUNTER("counter"),
    POWER("power"),
}

data class EnergyScale(val kind: EnergyKind, val factor: Double) {
    companion object {
        /** kWh = what the service returns × [factor]; the service integrates watts and sums the meter's own unit (spec §3.3). */
        fun of(unit: String?): EnergyScale? = when (unit?.trim()) {
            "kWh" -> EnergyScale(EnergyKind.COUNTER, 1.0)
            "Wh" -> EnergyScale(EnergyKind.COUNTER, 0.001)
            "W" -> EnergyScale(EnergyKind.POWER, 1.0)
            "kW" -> EnergyScale(EnergyKind.POWER, 1000.0)
            else -> null
        }
    }
}

data class HistoryPoint(val t: Long, val mean: Double, val min: Double, val max: Double)

data class Series(val windowSeconds: Long, val points: List<HistoryPoint>)

data class DayTotal(val t: Long, val kwh: Double)

data class DailyTotals(val days: List<DayTotal>)

sealed interface HistoryResult<out T> {
    data class Ok<T>(val value: T) : HistoryResult<T>
    data object NotRecorded : HistoryResult<Nothing>
    data object NotAllowed : HistoryResult<Nothing>
    data class Unavailable(val reason: String) : HistoryResult<Nothing>
}
