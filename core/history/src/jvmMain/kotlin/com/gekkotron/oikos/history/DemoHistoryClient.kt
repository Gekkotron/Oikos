package com.gekkotron.oikos.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin
import kotlin.random.Random

/** Deterministic fake history for the demo profile: same ref and range, same curve (history spec §4.5). */
class DemoHistoryClient(private val clock: () -> Long = System::currentTimeMillis) : HistoryClient {
    override val available: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()

    override suspend fun series(ref: HistoryRef, range: HistoryRange): HistoryResult<Series> {
        val window = when (range) {
            HistoryRange.H24 -> 86_400L
            HistoryRange.D7 -> 7 * 86_400L
            HistoryRange.D30 -> 30 * 86_400L
        }
        val step = window / POINTS
        val end = clock() / 1000 / step * step
        val rnd = Random(ref.hashCode() + range.ordinal)
        val key = ref.key.lowercase()
        val counter = isCounter(key)
        val temperature = !counter && (key.contains("temperature") || key.contains("température"))
        val solar = !counter && key.contains("solar")
        var total = 1_000.0 + rnd.nextDouble() * 500
        val points = (0 until POINTS).map { i ->
            val t = end - (POINTS - 1 - i) * step
            val h = hourOfDay(t)
            val mean = when {
                counter -> { total += (8.0 + rnd.nextDouble() * 6.0) * step / 86_400.0; total }
                solar -> if (h in 7.0..19.0) max(0.0, 4000 * sin(PI * (h - 7) / 12)) * (1 + noise(rnd, 0.05)) else 0.0
                temperature -> 21 + 2 * sin(2 * PI * (h - 15) / 24) + noise(rnd, 0.2)
                else -> (500 + 300 * sin(2 * PI * h / 24)) * (1 + noise(rnd, 0.05))
            }
            val spread = if (temperature) 0.3 else mean * 0.03
            HistoryPoint(t, mean, mean - spread, mean + spread)
        }
        return HistoryResult.Ok(Series(window, points))
    }

    override suspend fun daily(ref: HistoryRef, days: Int, scale: EnergyScale): HistoryResult<DailyTotals> {
        val today = clock() / 1000 / 86_400 * 86_400
        val rnd = Random(ref.hashCode() + days)
        return HistoryResult.Ok(DailyTotals((0 until days).map { i ->
            DayTotal(today - (days - 1 - i) * 86_400L, scaled(8.0 + rnd.nextDouble() * 6.0, scale))
        }))
    }

    override suspend fun today(ref: HistoryRef, scale: EnergyScale): HistoryResult<Double?> =
        HistoryResult.Ok(scaled(5.0 + Random(ref.hashCode()).nextDouble() * 4.0, scale))

    /** Each sensor flipped between 1 min and 3 days ago, the same for the same ref. */
    override suspend fun lastChanges(refs: List<HistoryRef>): HistoryResult<Map<HistoryRef, LastChange>> {
        val now = clock() / 1000
        return HistoryResult.Ok(refs.associateWith { LastChange.At(Instant.ofEpochSecond(now - Random(it.hashCode()).nextLong(60, 3 * 86_400L))) })
    }

    private fun scaled(kwh: Double, scale: EnergyScale) = round(kwh * scale.factor * 1000) / 1000

    private fun isCounter(key: String) = key.contains("kwh") || key.contains("energy") || key.contains("total")

    private fun noise(rnd: Random, amplitude: Double) = (rnd.nextDouble() * 2 - 1) * amplitude

    private fun hourOfDay(t: Long): Double {
        val z = Instant.ofEpochSecond(t).atZone(ZoneId.of("Europe/Paris"))
        return z.hour + z.minute / 60.0
    }

    private companion object {
        const val POINTS = 150
    }
}
