package com.gekkotron.oikos.feature.history.presentation

import com.gekkotron.oikos.history.DailyTotals
import com.gekkotron.oikos.history.HistoryRange
import com.gekkotron.oikos.history.Series

enum class ChartMode { POWER, DAILY }

sealed interface ChartContent {
    data object Loading : ChartContent
    data class Line(val series: Series, val min: Double, val avg: Double, val max: Double) : ChartContent
    data class Bars(val days: DailyTotals, val total: Double) : ChartContent {
        /** kWh per day over the days shown. */
        val avg: Double get() = if (days.days.isEmpty()) 0.0 else total / days.days.size
    }
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
    /** True when kWh-per-day bars exist for this target. */
    val energy: Boolean = false,
    /** True when the Power / kWh-per-day toggle applies (power sensors and the house, not kWh meters); shown on 7 d / 30 d. */
    val toggle: Boolean = false,
    val mode: ChartMode = ChartMode.POWER,
    val content: ChartContent = ChartContent.Loading,
    val missing: Boolean = false, // the entity is gone or has no history ref
)
