package com.gekkotron.oikos.feature.alerts.presentation

import com.gekkotron.oikos.alerts.demo.DemoAlerts
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals

class DayGroupsTest {
    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private val today = LocalDate.of(2026, 10, 7)

    @Test
    fun `alerts are grouped under Today, Yesterday and a date, newest first`() {
        val sections = groupByDay(DemoAlerts.all(now), ZoneOffset.UTC, today)
        assertEquals(listOf(DayLabel.Today, DayLabel.Yesterday, DayLabel.On(LocalDate.of(2026, 10, 5))), sections.map { it.label })
        assertEquals(listOf("demo-security", "demo-camera", "demo-alarm", "demo-energy"), sections[0].alerts.map { it.id })
        assertEquals(listOf("demo-climate"), sections[1].alerts.map { it.id })
        assertEquals(listOf("demo-system"), sections[2].alerts.map { it.id })
    }

    @Test
    fun `the day is the viewer's, not UTC`() {
        val lateEvening = DemoAlerts.all(now).first().copy(sentAt = Instant.parse("2026-10-06T23:30:00Z"))
        val paris = groupByDay(listOf(lateEvening), java.time.ZoneId.of("Europe/Paris"), today)
        assertEquals(DayLabel.Today, paris.single().label)
        assertEquals(DayLabel.Yesterday, groupByDay(listOf(lateEvening), ZoneOffset.UTC, today).single().label)
    }

    @Test
    fun `an out of order list still makes one section per day`() {
        val a = DemoAlerts.all(now)
        val shuffled = listOf(a[0], a[4], a[1])
        assertEquals(listOf(DayLabel.Today, DayLabel.Yesterday), groupByDay(shuffled, ZoneOffset.UTC, today).map { it.label })
        assertEquals(listOf("demo-security", "demo-camera"), groupByDay(shuffled, ZoneOffset.UTC, today)[0].alerts.map { it.id })
    }

    @Test
    fun `no alerts, no sections`() {
        assertEquals(emptyList(), groupByDay(emptyList(), ZoneOffset.UTC, today))
    }

    @Test
    fun `days stay correct across a DST change in a non UTC zone`() {
        val paris = java.time.ZoneId.of("Europe/Paris")
        // Paris leaves DST on 2026-10-25 (25 hour day); 23:30 local on the 24th is still "yesterday".
        val dayToday = LocalDate.of(2026, 10, 25)
        val a = DemoAlerts.all(now)
        val justAfterMidnight = a[0].copy(id = "x", sentAt = Instant.parse("2026-10-24T22:00:00Z")) // 00:00 on the 25th (CEST)
        val beforeMidnight = a[1].copy(id = "y", sentAt = Instant.parse("2026-10-24T21:59:00Z")) // 23:59 on the 24th
        val lateOnThe25th = a[2].copy(id = "z", sentAt = Instant.parse("2026-10-25T22:30:00Z")) // 23:30 on the 25th (CET)
        val sections = groupByDay(listOf(lateOnThe25th, justAfterMidnight, beforeMidnight), paris, dayToday)
        assertEquals(listOf(DayLabel.Today, DayLabel.Yesterday), sections.map { it.label })
        assertEquals(listOf("z", "x"), sections[0].alerts.map { it.id })
        assertEquals(listOf("y"), sections[1].alerts.map { it.id })
    }
}
