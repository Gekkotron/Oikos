package com.gekkotron.oikos.feature.alerts.presentation

import com.gekkotron.oikos.alerts.Alert
import java.time.LocalDate
import java.time.ZoneId

sealed interface DayLabel {
    data object Today : DayLabel

    data object Yesterday : DayLabel

    data class On(val date: LocalDate) : DayLabel
}

data class DaySection(val label: DayLabel, val alerts: List<Alert>)

/** One section per local day of `sentAt`, in order of first appearance, so a newest-first list stays newest first. */
fun groupByDay(alerts: List<Alert>, zone: ZoneId, today: LocalDate): List<DaySection> {
    val byDate = LinkedHashMap<LocalDate, MutableList<Alert>>()
    alerts.forEach { byDate.getOrPut(it.sentAt.atZone(zone).toLocalDate()) { mutableListOf() } += it }
    return byDate.map { (date, list) ->
        val label = when (date) {
            today -> DayLabel.Today
            today.minusDays(1) -> DayLabel.Yesterday
            else -> DayLabel.On(date)
        }
        DaySection(label, list)
    }
}
