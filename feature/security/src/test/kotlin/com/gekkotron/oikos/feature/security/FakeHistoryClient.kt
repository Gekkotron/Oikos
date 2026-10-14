package com.gekkotron.oikos.feature.security

import com.gekkotron.oikos.history.DailyTotals
import com.gekkotron.oikos.history.EnergyScale
import com.gekkotron.oikos.history.HistoryClient
import com.gekkotron.oikos.history.HistoryRange
import com.gekkotron.oikos.history.HistoryRef
import com.gekkotron.oikos.history.HistoryResult
import com.gekkotron.oikos.history.LastChange
import com.gekkotron.oikos.history.Series
import kotlinx.coroutines.flow.MutableStateFlow

class FakeHistoryClient : HistoryClient {
    override val available = MutableStateFlow(true)
    val asked = mutableListOf<List<HistoryRef>>()
    var answer: HistoryResult<Map<HistoryRef, LastChange>> = HistoryResult.Ok(emptyMap())

    override suspend fun series(ref: HistoryRef, range: HistoryRange): HistoryResult<Series> = HistoryResult.Unavailable("unused")
    override suspend fun daily(ref: HistoryRef, days: Int, scale: EnergyScale): HistoryResult<DailyTotals> = HistoryResult.Unavailable("unused")
    override suspend fun today(ref: HistoryRef, scale: EnergyScale): HistoryResult<Double?> = HistoryResult.Unavailable("unused")
    override suspend fun lastChanges(refs: List<HistoryRef>): HistoryResult<Map<HistoryRef, LastChange>> {
        asked += refs
        return answer
    }
}
