package com.gekkotron.oikos.feature.energy.history

import com.gekkotron.oikos.feature.energy.presentation.EnergyMeters
import com.gekkotron.oikos.history.DailyTotals
import com.gekkotron.oikos.history.DayTotal
import com.gekkotron.oikos.history.EnergyHistory
import com.gekkotron.oikos.history.EnergyScale
import com.gekkotron.oikos.history.HistoryClient
import com.gekkotron.oikos.history.HistoryPoint
import com.gekkotron.oikos.history.HistoryRange
import com.gekkotron.oikos.history.HistoryRefs
import com.gekkotron.oikos.history.HistoryResult
import com.gekkotron.oikos.history.Series
import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.PowerUnits
import com.gekkotron.oikos.source.EntitySource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject

/**
 * The house's history from its energy entities, by the Energy screen's rules (history spec §5.3): a HOUSE sensor
 * answers for itself, else the house is derived from solar, grid and battery like its live watts and its total.
 */
class EnergyHistoryImpl @Inject constructor(
    private val source: EntitySource,
    private val history: HistoryClient,
) : EnergyHistory {
    override fun meterFor(entityId: String): String? {
        val entities = source.entities.value
        return entities[entityId]?.let { EnergyMeters.meterFor(it, entities.values) }?.uniqueId
    }

    override suspend fun houseSeries(range: HistoryRange): HistoryResult<Series> {
        val byRole = byRole()
        byRole[EnergyRole.HOUSE]?.let { house -> return powerSum(range, listOf(Group(house)), clamp = false) }
        // Solar is clamped on its own first, like the live solar node; the grid is import − export.
        val groups = listOf(
            Group(byRole[EnergyRole.SOLAR].orEmpty(), clamp = true),
            Group(byRole[EnergyRole.GRID].orEmpty() + byRole[EnergyRole.GRID_EXPORT].orEmpty()),
            Group(byRole[EnergyRole.BATTERY].orEmpty()),
        ).filter { it.entities.isNotEmpty() }
        return powerSum(range, groups, clamp = true)
    }

    override suspend fun houseDaily(days: Int): HistoryResult<DailyTotals> {
        val all = source.entities.value.values
        val byRole = byRole()
        byRole[EnergyRole.HOUSE]?.let { house ->
            val meters = EnergyMeters.nodeMeters(house, all)
            if (meters.isEmpty()) return HistoryResult.NotRecorded
            return dailySum(days, meters, emptyList(), clamp = false)
        }
        // EnergyFlowBuilder's derived total, per day.
        val meters = EnergyMeters.houseMeters(byRole, all) ?: return HistoryResult.NotRecorded
        return dailySum(days, meters.plus, meters.minus, clamp = true)
    }

    /** Entities that sum together; a clamped group is floored at zero before joining the total. */
    private class Group(val entities: List<Entity>, val clamp: Boolean = false)

    private fun byRole(): Map<EnergyRole, List<Entity>> =
        source.entities.value.values.filter { it.ui.energyRole != null }.sortedBy { it.uniqueId }.groupBy { it.ui.energyRole!! }

    private suspend fun powerSum(range: HistoryRange, groups: List<Group>, clamp: Boolean): HistoryResult<Series> {
        if (groups.isEmpty()) return HistoryResult.NotRecorded
        val inputs = groups.flatMap { g -> g.entities.map { it to g } }.map { (e, g) ->
            val ref = HistoryRefs.of(e) ?: return HistoryResult.NotRecorded
            Triple(ref, wattsPerUnit(e) ?: return HistoryResult.NotRecorded, g)
        }
        val results = coroutineScope { inputs.map { (ref) -> async { history.series(ref, range) } }.awaitAll() }
        val series = results.map { r -> (r as? HistoryResult.Ok)?.value ?: return r }
        val byTime = series.map { s -> s.points.associateBy { it.t } }
        val shared = byTime.map { it.keys }.reduce { a, b -> a intersect b }
        val byGroup = inputs.indices.groupBy { inputs[it].third }
        val points = series.first().points.map { it.t }.filter { it in shared }.map { t ->
            val total = groups.map { g ->
                val sum = byGroup.getValue(g).map { i -> scaled(byTime[i].getValue(t), inputs[i].second) }.reduce(::plus)
                if (g.clamp) floored(sum) else sum
            }.reduce(::plus)
            if (clamp) floored(total) else total
        }
        return HistoryResult.Ok(Series(series.first().windowSeconds, points))
    }

    private suspend fun dailySum(days: Int, plus: List<Entity>, minus: List<Entity>, clamp: Boolean): HistoryResult<DailyTotals> {
        val meters = (plus + minus).map { m ->
            val ref = HistoryRefs.of(m) ?: return HistoryResult.NotRecorded
            ref to (EnergyScale.of(m.unit) ?: return HistoryResult.NotRecorded)
        }
        val results = coroutineScope { meters.map { (ref, scale) -> async { history.daily(ref, days, scale) } }.awaitAll() }
        val totals = results.map { r -> (r as? HistoryResult.Ok)?.value?.days?.associate { it.t to it.kwh } ?: return r }
        val shared = totals.map { it.keys }.reduce { a, b -> a intersect b }
        val out = totals.first().keys.filter { it in shared }.map { t ->
            val kwh = totals.take(plus.size).sumOf { it.getValue(t) } - totals.drop(plus.size).sumOf { it.getValue(t) }
            DayTotal(t, if (clamp) kwh.coerceAtLeast(0.0) else kwh)
        }
        return HistoryResult.Ok(DailyTotals(out))
    }

    /** Watts per unit of [e]'s value, negative when a signed role is inverted or the role is an export (as live). */
    private fun wattsPerUnit(e: Entity): Double? {
        val factor = PowerUnits.factor(e.unit) ?: return null
        val role = e.ui.energyRole
        val sign = if (e.ui.energyInvert && role?.signed == true) -1.0 else 1.0
        return factor * sign * if (role == EnergyRole.GRID_EXPORT) -1.0 else 1.0
    }

    private fun scaled(p: HistoryPoint, k: Double): HistoryPoint =
        if (k >= 0) HistoryPoint(p.t, p.mean * k, p.min * k, p.max * k) else HistoryPoint(p.t, p.mean * k, p.max * k, p.min * k)

    private fun plus(a: HistoryPoint, b: HistoryPoint) = HistoryPoint(a.t, a.mean + b.mean, a.min + b.min, a.max + b.max)

    private fun floored(p: HistoryPoint) = HistoryPoint(p.t, p.mean.coerceAtLeast(0.0), p.min.coerceAtLeast(0.0), p.max.coerceAtLeast(0.0))
}
