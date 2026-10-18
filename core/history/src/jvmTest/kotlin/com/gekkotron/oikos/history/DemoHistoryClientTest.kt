package com.gekkotron.oikos.history

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DemoHistoryClientTest {
    private val demo = DemoHistoryClient(clock = { 1_696_000_000_000 })

    @Test
    fun `about 150 rising-in-time points per range, same answer twice`(): Unit = runBlocking {
        HistoryRange.entries.forEach { range ->
            val s = assertIs<HistoryResult.Ok<Series>>(demo.series(HistoryRef("demo/sensor", "temperature"), range)).value
            assertTrue(s.points.size in 140..151, "$range ${s.points.size}")
            assertTrue(s.points.zipWithNext().all { (a, b) -> a.t < b.t })
            assertTrue(s.points.all { it.min <= it.mean && it.mean <= it.max })
            assertEquals(s, (demo.series(HistoryRef("demo/sensor", "temperature"), range) as HistoryResult.Ok).value)
        }
    }

    @Test
    fun `solar is never negative and daily has one value per day`(): Unit = runBlocking {
        val s = (demo.series(HistoryRef("demo/energy", "solar_w"), HistoryRange.H24) as HistoryResult.Ok).value
        assertTrue(s.points.all { it.mean >= 0 })
        val d = (demo.daily(HistoryRef("demo/energy", "solar_kwh"), 7, EnergyScale(EnergyKind.COUNTER, 1.0)) as HistoryResult.Ok).value
        assertEquals(7, d.days.size)
        assertTrue(demo.available.value)
    }

    @Test
    fun `daily and today follow the scale factor`(): Unit = runBlocking {
        val ref = HistoryRef("demo/energy", "total_kwh")
        val one = (demo.daily(ref, 3, EnergyScale(EnergyKind.COUNTER, 1.0)) as HistoryResult.Ok).value
        val milli = (demo.daily(ref, 3, EnergyScale(EnergyKind.COUNTER, 0.001)) as HistoryResult.Ok).value
        assertTrue(one.days.zip(milli.days).all { (a, b) -> kotlin.math.abs(a.kwh * 0.001 - b.kwh) < 0.0006 })
        assertTrue((demo.today(ref, EnergyScale(EnergyKind.COUNTER, 1.0)) as HistoryResult.Ok).value!! in 5.0..9.0)
    }

    @Test
    fun `lastChanges answers every ref with a past time, the same twice`(): Unit = runBlocking {
        val now = 1_760_000_000_000L
        val c = DemoHistoryClient(clock = { now })
        val refs = listOf(HistoryRef("demo/door", "contact"), HistoryRef("demo/hall", "occupancy"))
        val a = assertIs<HistoryResult.Ok<Map<HistoryRef, LastChange>>>(c.lastChanges(refs)).value
        assertEquals(refs.toSet(), a.keys)
        a.values.forEach {
            val at = assertIs<LastChange.At>(it).at
            assertTrue(at < Instant.ofEpochMilli(now) && at > Instant.ofEpochMilli(now).minusSeconds(3 * 86_400))
        }
        assertEquals(a, assertIs<HistoryResult.Ok<Map<HistoryRef, LastChange>>>(c.lastChanges(refs)).value)
    }
}
