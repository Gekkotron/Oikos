package com.gekkotron.oikos.feature.history.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.feature.history.HistoryTarget
import com.gekkotron.oikos.history.DailyTotals
import com.gekkotron.oikos.history.EnergyHistory
import com.gekkotron.oikos.history.EnergyKind
import com.gekkotron.oikos.history.EnergyScale
import com.gekkotron.oikos.history.HistoryClient
import com.gekkotron.oikos.history.HistoryRange
import com.gekkotron.oikos.history.HistoryRefs
import com.gekkotron.oikos.history.HistoryResult
import com.gekkotron.oikos.history.Series
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.source.EntitySource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val source: EntitySource,
    private val history: HistoryClient,
    private val energy: EnergyHistory,
) : ViewModel() {
    private data class Selection(
        val target: HistoryTarget? = null,
        val range: HistoryRange = HistoryRange.H24,
        val mode: ChartMode = ChartMode.POWER,
        val attempt: Int = 0,
    )

    private val selection = MutableStateFlow(Selection())

    private val content = selection.flatMapLatest { s ->
        flow {
            emit(ChartContent.Loading)
            emit(load(s))
        }
    }

    val state: StateFlow<HistoryUi> = combine(
        selection, source.entities, source.states(), source.areas, content,
    ) { s, entities, states, areas, chart ->
        val base = when (val target = s.target) {
            null -> HistoryUi()
            HistoryTarget.House -> HistoryUi(unit = "W", energy = true, toggle = true)
            is HistoryTarget.Entity -> {
                val entity = entities[target.id]
                if (entity == null || HistoryRefs.of(entity) == null) {
                    HistoryUi(missing = true)
                } else {
                    val kind = EnergyScale.of(entity.unit)?.kind
                    val binary = entity.kind == Kind.BINARY_SENSOR
                    HistoryUi(
                        title = entity.name,
                        room = entity.area.takeIf { it != UNASSIGNED_AREA }?.let { id -> areas.firstOrNull { it.id == id }?.name },
                        // No numeric readout for a 0/1 sensor.
                        live = if (binary) null else states[target.id]?.let(::numeric),
                        unit = entity.unit,
                        energy = kind != null,
                        // A kWh meter has no power line to switch to: only its index (24 h) or its daily bars exist.
                        toggle = kind == EnergyKind.POWER,
                        binary = binary,
                        binaryClass = entity.deviceClass.takeIf { binary },
                    )
                }
            }
        }
        base.copy(range = s.range, mode = effectiveMode(s, base.energy && !base.toggle), content = if (base.missing || s.target == null) ChartContent.Loading else chart)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUi())

    /** A kWh meter (no power line) shows its daily bars on 7 d / 30 d whatever was picked. */
    private fun effectiveMode(s: Selection, counter: Boolean) =
        if (counter && s.range != HistoryRange.H24) ChartMode.DAILY else s.mode

    fun bind(target: HistoryTarget) = selection.update { if (it.target == target) it else it.copy(target = target, attempt = it.attempt + 1) }

    fun selectRange(range: HistoryRange) = selection.update {
        it.copy(range = range, mode = if (range == HistoryRange.H24) ChartMode.POWER else it.mode)
    }

    fun selectMode(mode: ChartMode) = selection.update { it.copy(mode = mode) }

    fun retry() = selection.update { it.copy(attempt = it.attempt + 1) }

    private suspend fun load(s: Selection): ChartContent {
        val days = when (s.range) {
            HistoryRange.D7 -> 7
            HistoryRange.Y1 -> 365
            else -> 30
        }
        val daily = s.mode == ChartMode.DAILY && s.range != HistoryRange.H24
        return when (val target = s.target) {
            null -> ChartContent.Loading
            HistoryTarget.House -> if (daily) bars(energy.houseDaily(days)) else line(energy.houseSeries(s.range))
            is HistoryTarget.Entity -> {
                // Waits for the entity to exist (cold start); a state tick never refetches. A gone entity stays Loading, the header says missing.
                val entity = source.entities.first { target.id in it }.getValue(target.id)
                val ref = HistoryRefs.of(entity) ?: return ChartContent.Loading
                val scale = EnergyScale.of(entity.unit)
                val counter = scale?.kind == EnergyKind.COUNTER
                if ((daily || counter && s.range != HistoryRange.H24) && scale != null) {
                    val meter = if (scale.kind == EnergyKind.POWER) meterOf(target.id) else null
                    if (meter != null) bars(history.daily(meter.first, days, meter.second)) else bars(history.daily(ref, days, scale))
                } else {
                    line(history.series(ref, s.range))
                }
            }
        }
    }

    private fun meterOf(id: String) = energy.meterFor(id)
        ?.let { source.entities.value[it] }
        ?.let { m -> HistoryRefs.of(m)?.let { ref -> EnergyScale.of(m.unit)?.takeIf { it.kind == EnergyKind.COUNTER }?.let { ref to it } } }

    private fun line(r: HistoryResult<Series>): ChartContent = when (r) {
        is HistoryResult.Ok -> {
            val p = r.value.points
            if (p.isEmpty()) ChartContent.NoData
            else ChartContent.Line(r.value, p.minOf { it.min }, p.map { it.mean }.average(), p.maxOf { it.max })
        }
        else -> failure(r)
    }

    private fun bars(r: HistoryResult<DailyTotals>): ChartContent = when (r) {
        is HistoryResult.Ok ->
            if (r.value.days.isEmpty()) ChartContent.NoData else ChartContent.Bars(r.value, r.value.days.sumOf { it.kwh })
        else -> failure(r)
    }

    private fun failure(r: HistoryResult<*>): ChartContent = when (r) {
        HistoryResult.NotRecorded -> ChartContent.NotRecorded
        HistoryResult.NotAllowed -> ChartContent.NotAllowed
        else -> ChartContent.Unavailable
    }

    private fun numeric(s: EntityState): Double? = when (val v = s.value) {
        is StateValue.Numeric -> v.value
        is StateValue.Climate -> v.current
        else -> null
    }
}
