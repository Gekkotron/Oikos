package com.gekkotron.oikos.feature.energy.history

import com.gekkotron.oikos.feature.energy.FakeEntitySource
import com.gekkotron.oikos.feature.energy.energyEntity
import com.gekkotron.oikos.feature.energy.recorded
import com.gekkotron.oikos.history.DailyTotals
import com.gekkotron.oikos.history.DayTotal
import com.gekkotron.oikos.history.EnergyKind
import com.gekkotron.oikos.history.HistoryPoint
import com.gekkotron.oikos.history.HistoryRange
import com.gekkotron.oikos.history.HistoryResult
import com.gekkotron.oikos.history.Series
import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.Entity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnergyHistoryImplTest {
    private val source = FakeEntitySource()
    private val history = FakeHistoryClient()
    private val impl = EnergyHistoryImpl(source, history)

    private fun power(id: String, role: EnergyRole?, device: String, unit: String = "W", invert: Boolean = false): Entity =
        energyEntity(id, role, unit = unit, invert = invert).copy(deviceId = device).recorded("power")

    private fun counter(id: String, device: String, unit: String = "kWh"): Entity =
        energyEntity(id, null, unit = unit).copy(deviceId = device).recorded("energy")

    private fun given(vararg entities: Entity) {
        source.entities.value = entities.associateBy { it.uniqueId }
    }

    private fun series(vararg byId: Pair<String, List<HistoryPoint>>) {
        val map = byId.toMap()
        history.series = { ref, _ -> HistoryResult.Ok(Series(600, map.getValue(ref.id))) }
    }

    private fun daily(vararg byId: Pair<String, List<DayTotal>>) {
        val map = byId.toMap()
        history.daily = { ref, _, _ -> HistoryResult.Ok(DailyTotals(map.getValue(ref.id))) }
    }

    /** Solar with its meter, grid with its import and export meters: the house a derived total needs. */
    private val solarAndGrid = arrayOf(
        power("solar_power", EnergyRole.SOLAR, "solar"),
        counter("solar_energy", "solar"),
        power("grid_power", EnergyRole.GRID, "grid"),
        counter("grid_energy", "grid"),
        counter("grid_energy_produced", "grid"),
    )

    @Test
    fun `house series is the clamped sum of solar and grid at shared timestamps`() = runBlocking {
        given(power("solar", EnergyRole.SOLAR, "s"), power("grid", EnergyRole.GRID, "g"))
        history.series = { ref, _ ->
            HistoryResult.Ok(
                Series(
                    600,
                    when (ref.id) {
                        "solar" -> listOf(HistoryPoint(1, 3000.0, 2900.0, 3100.0), HistoryPoint(2, 2000.0, 1900.0, 2100.0), HistoryPoint(3, 0.0, 0.0, 0.0))
                        else -> listOf(HistoryPoint(1, -500.0, -600.0, -400.0), HistoryPoint(3, 400.0, 300.0, 500.0))
                    },
                ),
            )
        }
        val s = (impl.houseSeries(HistoryRange.H24) as HistoryResult.Ok).value
        assertEquals(listOf(HistoryPoint(1, 2500.0, 2300.0, 2700.0), HistoryPoint(3, 400.0, 300.0, 500.0)), s.points)
        assertEquals(600, s.windowSeconds)
    }

    @Test
    fun `house series clamps a negative sum to zero`() = runBlocking {
        given(power("solar", EnergyRole.SOLAR, "s"), power("grid", EnergyRole.GRID, "g"))
        series(
            "solar" to listOf(HistoryPoint(1, 1000.0, 900.0, 1100.0)),
            "grid" to listOf(HistoryPoint(1, -1200.0, -1300.0, -1000.0)),
        )
        assertEquals(listOf(HistoryPoint(1, 0.0, 0.0, 100.0)), (impl.houseSeries(HistoryRange.H24) as HistoryResult.Ok).value.points)
    }

    @Test
    fun `house series scales kW, flips an inverted grid and subtracts grid export`() = runBlocking {
        given(
            power("solar", EnergyRole.SOLAR, "s", unit = "kW"),
            power("grid", EnergyRole.GRID, "g", invert = true),
            power("export", EnergyRole.GRID_EXPORT, "e"),
        )
        series(
            "solar" to listOf(HistoryPoint(1, 2.0, 1.5, 2.5)),
            // Inverted: −300 W reads as a 300 W import; its min and max swap when flipped.
            "grid" to listOf(HistoryPoint(1, -300.0, -400.0, -200.0)),
            "export" to listOf(HistoryPoint(1, 500.0, 400.0, 600.0)),
        )
        // 2000 + 300 − 500; min 1500 + 200 − 600; max 2500 + 400 − 400.
        assertEquals(listOf(HistoryPoint(1, 1800.0, 1100.0, 2500.0)), (impl.houseSeries(HistoryRange.H24) as HistoryResult.Ok).value.points)
    }

    @Test
    fun `a battery adds to the house series, charging subtracts`() = runBlocking {
        given(power("solar", EnergyRole.SOLAR, "s"), power("battery", EnergyRole.BATTERY, "b"))
        series(
            "solar" to listOf(HistoryPoint(1, 3000.0, 2800.0, 3200.0), HistoryPoint(2, 0.0, 0.0, 0.0)),
            "battery" to listOf(HistoryPoint(1, -1000.0, -1200.0, -800.0), HistoryPoint(2, 600.0, 500.0, 700.0)),
        )
        assertEquals(
            listOf(HistoryPoint(1, 2000.0, 1600.0, 2400.0), HistoryPoint(2, 600.0, 500.0, 700.0)),
            (impl.houseSeries(HistoryRange.H24) as HistoryResult.Ok).value.points,
        )
    }

    @Test
    fun `a measured house is its own series`() = runBlocking {
        given(power("house", EnergyRole.HOUSE, "h"), power("solar", EnergyRole.SOLAR, "s"))
        series("house" to listOf(HistoryPoint(1, 700.0, 600.0, 800.0)))
        assertEquals(listOf(HistoryPoint(1, 700.0, 600.0, 800.0)), (impl.houseSeries(HistoryRange.H24) as HistoryResult.Ok).value.points)
        assertEquals(listOf("series house 24h"), history.calls)
    }

    @Test
    fun `no inputs or an unrecorded input makes the house series NotRecorded`() = runBlocking {
        given(power("plug", EnergyRole.CONSUMER, "p"))
        assertEquals(HistoryResult.NotRecorded, impl.houseSeries(HistoryRange.H24))
        // A grid entity without a unit cannot produce a HistoryRef, so the whole house is NotRecorded.
        given(power("solar", EnergyRole.SOLAR, "s"), energyEntity("grid", EnergyRole.GRID, unit = null).copy(deviceId = "g"))
        assertEquals(HistoryResult.NotRecorded, impl.houseSeries(HistoryRange.H24))
    }

    @Test
    fun `house daily is plus meters minus export and draw meters`() = runBlocking {
        given(*solarAndGrid)
        daily(
            "solar_energy" to listOf(DayTotal(1, 10.0), DayTotal(2, 8.0), DayTotal(3, 5.0)),
            "grid_energy" to listOf(DayTotal(1, 4.0), DayTotal(2, 2.0)),
            "grid_energy_produced" to listOf(DayTotal(1, 3.0), DayTotal(2, 12.0)),
        )
        val d = (impl.houseDaily(7) as HistoryResult.Ok).value
        // Day 1: 10 + 4 − 3; day 2 clamps 8 + 2 − 12 to 0; day 3 is missing from the grid meters.
        assertEquals(listOf(DayTotal(1, 11.0), DayTotal(2, 0.0)), d.days)
        assertEquals(setOf(EnergyKind.COUNTER), history.calls.map { EnergyKind.valueOf(it.substringAfterLast(' ')) }.toSet())
    }

    @Test
    fun `house daily subtracts the inverter draw when solar counts its production twin`() = runBlocking {
        given(
            power("inv_power_a", EnergyRole.SOLAR, "inv"),
            counter("inv_energy_produced_a", "inv"),
            counter("inv_energy_a", "inv"),
            power("grid_power", EnergyRole.GRID, "grid"),
            counter("grid_energy", "grid"),
            counter("grid_energy_produced", "grid"),
        )
        daily(
            "inv_energy_produced_a" to listOf(DayTotal(1, 10.0)),
            "inv_energy_a" to listOf(DayTotal(1, 0.5)),
            "grid_energy" to listOf(DayTotal(1, 4.0)),
            "grid_energy_produced" to listOf(DayTotal(1, 3.0)),
        )
        // 10 + 4 − 3 − 0.5.
        assertEquals(listOf(DayTotal(1, 10.5)), (impl.houseDaily(7) as HistoryResult.Ok).value.days)
    }

    @Test
    fun `a measured house totals its own meter`() = runBlocking {
        given(power("house_power", EnergyRole.HOUSE, "h"), counter("house_energy", "h", unit = "Wh"), *solarAndGrid)
        var scale: Double? = null
        history.daily = { _, _, s -> scale = s.factor; HistoryResult.Ok(DailyTotals(listOf(DayTotal(1, 6.0)))) }
        assertEquals(listOf(DayTotal(1, 6.0)), (impl.houseDaily(7) as HistoryResult.Ok).value.days)
        assertEquals(0.001, scale)
        assertEquals(listOf("daily house_energy 7 COUNTER"), history.calls)
    }

    @Test
    fun `a battery or a missing meter makes the house daily NotRecorded`() = runBlocking {
        given(*solarAndGrid, power("battery", EnergyRole.BATTERY, "b"))
        assertEquals(HistoryResult.NotRecorded, impl.houseDaily(7))

        // The grid has no counter to pair with.
        given(power("solar_power", EnergyRole.SOLAR, "solar"), counter("solar_energy", "solar"), power("grid_power", EnergyRole.GRID, "grid"))
        assertEquals(HistoryResult.NotRecorded, impl.houseDaily(7))

        // Solar without any export meter would count the export as consumption.
        given(power("solar_power", EnergyRole.SOLAR, "solar"), counter("solar_energy", "solar"), power("grid_power", EnergyRole.GRID, "grid"), counter("grid_energy", "grid"))
        assertEquals(HistoryResult.NotRecorded, impl.houseDaily(7))
        assertEquals(emptyList(), history.calls)
    }

    @Test
    fun `an input failure is passed through`() = runBlocking {
        given(*solarAndGrid)
        history.series = { _, _ -> HistoryResult.NotAllowed }
        assertEquals(HistoryResult.NotAllowed, impl.houseSeries(HistoryRange.H24))
        history.daily = { ref, _, _ -> if (ref.id.endsWith("grid_energy")) HistoryResult.Unavailable("down") else HistoryResult.Ok(DailyTotals(emptyList())) }
        assertEquals(HistoryResult.Unavailable("down"), impl.houseDaily(7))
    }

    @Test
    fun `meterFor follows the energy pairing`() {
        given(power("plug_power", EnergyRole.CONSUMER, "plug"), counter("plug_energy", "plug"))
        assertEquals("plug_energy", impl.meterFor("plug_power"))
        assertNull(impl.meterFor("nothing"))
    }
}
