package com.gekkotron.oikos.history

interface EnergyHistory {
    /** The kWh meter paired with a power entity by the Energy rules, or null. */
    fun meterFor(entityId: String): String?
    suspend fun houseSeries(range: HistoryRange): HistoryResult<Series>
    suspend fun houseDaily(days: Int): HistoryResult<DailyTotals>

    companion object {
        /** No energy wiring: nothing pairs, nothing answers. */
        val None: EnergyHistory = object : EnergyHistory {
            private val off = HistoryResult.Unavailable("no energy")
            override fun meterFor(entityId: String): String? = null
            override suspend fun houseSeries(range: HistoryRange) = off
            override suspend fun houseDaily(days: Int) = off
        }
    }
}
