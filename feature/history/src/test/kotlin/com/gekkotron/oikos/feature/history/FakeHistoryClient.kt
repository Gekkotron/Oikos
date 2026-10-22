package com.gekkotron.oikos.feature.history

import com.gekkotron.oikos.history.DailyTotals
import com.gekkotron.oikos.history.DayTotal
import com.gekkotron.oikos.history.EnergyHistory
import com.gekkotron.oikos.history.EnergyScale
import com.gekkotron.oikos.history.HistoryClient
import com.gekkotron.oikos.history.HistoryPoint
import com.gekkotron.oikos.history.HistoryRange
import com.gekkotron.oikos.history.HistoryRef
import com.gekkotron.oikos.history.HistoryResult
import com.gekkotron.oikos.history.Series
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

private fun okSeries() = HistoryResult.Ok(Series(600, listOf(HistoryPoint(1, 20.0, 19.0, 21.0), HistoryPoint(2, 22.0, 21.0, 23.0))))
private fun okDaily() = HistoryResult.Ok(DailyTotals(listOf(DayTotal(1, 3.0), DayTotal(2, 4.5))))

class FakeHistoryClient : HistoryClient {
    override val available = MutableStateFlow(true)
    val calls = mutableListOf<String>()
    var series: (HistoryRef, HistoryRange) -> HistoryResult<Series> = { _, _ -> okSeries() }
    var daily: (HistoryRef, Int, EnergyScale) -> HistoryResult<DailyTotals> = { _, _, _ -> okDaily() }

    /** When set, a 24 h series() waits on it (to test late answers). */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun series(ref: HistoryRef, range: HistoryRange): HistoryResult<Series> {
        calls += "series ${ref.id} ${range.query}"
        gate?.takeIf { range == HistoryRange.H24 }?.await()
        return this.series.invoke(ref, range)
    }

    override suspend fun daily(ref: HistoryRef, days: Int, scale: EnergyScale): HistoryResult<DailyTotals> {
        calls += "daily ${ref.id} $days ${scale.kind}"
        return this.daily.invoke(ref, days, scale)
    }

    override suspend fun today(ref: HistoryRef, scale: EnergyScale): HistoryResult<Double?> = HistoryResult.Ok(null)
}

class FakeEnergyHistory(val meters: MutableMap<String, String> = mutableMapOf()) : EnergyHistory {
    override fun meterFor(entityId: String): String? = meters[entityId]
    override suspend fun houseSeries(range: HistoryRange): HistoryResult<Series> = okSeries()
    override suspend fun houseDaily(days: Int): HistoryResult<DailyTotals> = okDaily()
}
