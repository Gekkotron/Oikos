package com.gekkotron.oikos.feature.history.presentation

import com.gekkotron.oikos.history.DailyTotals
import com.gekkotron.oikos.history.HistoryRange
import com.gekkotron.oikos.history.Series

enum class ChartMode { POWER, DAILY }

sealed interface ChartContent {
    data object Loading : ChartContent
    data class Line(val series: Series, val min: Double, val avg: Double, val max: Double) : ChartContent
    data class Bars(val days: DailyTotals, val total: Double) : ChartContent
    data object NoData : ChartContent
    data object NotRecorded : ChartContent
    data object NotAllowed : ChartContent
    data object Unavailable : ChartContent
}

data class HistoryUi(
    val title: String? = null, // null = the house (the UI shows the localized "House")
    val room: String? = null,
    val live: Double? = null,
    val unit: String? = null,
    val range: HistoryRange = HistoryRange.H24,
    /** True when kWh-per-day bars exist for this target; the toggle shows on 7 d / 30 d. */
    val energy: Boolean = false,
    val mode: ChartMode = ChartMode.POWER,
    val content: ChartContent = ChartContent.Loading,
    val missing: Boolean = false, // the entity is gone or has no history ref
)
