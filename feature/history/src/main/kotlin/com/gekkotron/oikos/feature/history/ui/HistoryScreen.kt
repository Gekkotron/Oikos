package com.gekkotron.oikos.feature.history.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.charts.ChartBar
import com.gekkotron.oikos.designsystem.charts.ChartPoint
import com.gekkotron.oikos.designsystem.charts.ChartSkeleton
import com.gekkotron.oikos.designsystem.charts.DailyBarsChart
import com.gekkotron.oikos.designsystem.charts.HistoryLineChart
import com.gekkotron.oikos.feature.history.HistoryTarget
import com.gekkotron.oikos.feature.history.R
import com.gekkotron.oikos.feature.history.presentation.ChartContent
import com.gekkotron.oikos.feature.history.presentation.ChartMode
import com.gekkotron.oikos.feature.history.presentation.HistoryUi
import com.gekkotron.oikos.feature.history.presentation.HistoryViewModel
import com.gekkotron.oikos.history.HistoryRange
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.gekkotron.oikos.designsystem.R as DsR

@Composable
fun HistoryRoute(
    target: HistoryTarget,
    onBack: () -> Unit,
    windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    LaunchedEffect(target) { viewModel.bind(target) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    HistoryScreen(
        state, onBack,
        onRange = viewModel::selectRange, onMode = viewModel::selectMode, onRetry = viewModel::retry,
        windowInsets = windowInsets,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    state: HistoryUi,
    onBack: () -> Unit,
    onRange: (HistoryRange) -> Unit,
    onMode: (ChartMode) -> Unit,
    onRetry: () -> Unit,
    windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
) {
    val backLabel = stringResource(R.string.history_back)
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = OikosTheme.colors.content,
        contentWindowInsets = windowInsets,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = backLabel }) {
                        Text("←", style = OikosTheme.type.cardValue, modifier = Modifier.clearAndSetSemantics {})
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = OikosTheme.colors.content,
                    navigationIconContentColor = OikosTheme.colors.content,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(state, locale)
            RangeChips(state.range, onRange)
            if (state.toggle && state.range != HistoryRange.H24) ModeToggle(state.mode, onMode)
            Body(state, onRetry, zone, locale)
        }
    }
}

@Composable
private fun Header(state: HistoryUi, locale: Locale) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(state.title ?: stringResource(DsR.string.energy_house), style = OikosTheme.type.cardName)
        state.room?.let { Text(it, style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted) }
        state.live?.let { Text(withUnit(it, state.unit, locale), style = OikosTheme.type.display) }
    }
}

@Composable
private fun RangeChips(selected: HistoryRange, onRange: (HistoryRange) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HistoryRange.entries.forEach { range ->
            FilterChip(
                selected = range == selected,
                onClick = { onRange(range) },
                label = { Text(stringResource(rangeLabel(range))) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeToggle(mode: ChartMode, onMode: (ChartMode) -> Unit) {
    val modes = ChartMode.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, m ->
            SegmentedButton(
                selected = m == mode,
                onClick = { onMode(m) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
            ) {
                Text(stringResource(if (m == ChartMode.POWER) R.string.history_mode_power else R.string.history_mode_daily))
            }
        }
    }
}

@Composable
private fun Body(state: HistoryUi, onRetry: () -> Unit, zone: ZoneId, locale: Locale) {
    // A missing entity keeps `content = Loading`, so it is checked first; it can be briefly true on a cold start.
    if (state.missing) return Message(R.string.history_not_recorded)
    when (val content = state.content) {
        ChartContent.Loading -> ChartSkeleton()
        is ChartContent.Line -> LineBody(state, content, zone, locale)
        is ChartContent.Bars -> BarsBody(state, content, zone, locale)
        ChartContent.NoData -> Message(R.string.history_no_data)
        ChartContent.NotRecorded -> Message(R.string.history_not_recorded)
        ChartContent.NotAllowed -> Message(R.string.history_not_allowed)
        ChartContent.Unavailable -> {
            Message(R.string.history_unavailable)
            Button(onClick = onRetry) { Text(stringResource(R.string.history_retry)) }
        }
    }
}

@Composable
private fun Message(res: Int) {
    Text(stringResource(res), style = OikosTheme.type.body, color = OikosTheme.colors.contentMuted, modifier = Modifier.padding(vertical = 24.dp))
}

@Composable
private fun LineBody(state: HistoryUi, line: ChartContent.Line, zone: ZoneId, locale: Locale) {
    val unit = state.unit
    val title = state.title ?: stringResource(DsR.string.energy_house)
    val formatTime = timeFormatter(state.range, zone, locale)
    if (state.binary) {
        // Binary sensors chart as 0/1 steps: use max within each bucket as "was active at any point".
        val points = remember(line) { line.series.points.map { ChartPoint(it.t, if (it.max >= 0.5) 1.0 else 0.0, 0.0, 1.0) } }
        val on = stringResource(binaryOnLabel(state.binaryClass))
        val off = stringResource(binaryOffLabel(state.binaryClass))
        val description = stringResource(R.string.history_cd_step, title, stringResource(rangeDescription(state.range)))
        HistoryLineChart(
            points = points,
            color = OikosTheme.colors.water,
            formatValue = { if (it >= 0.5) on else off },
            formatTime = formatTime,
            contentDescription = description,
            step = true,
        )
        return
    }
    val points = remember(line) { line.series.points.map { ChartPoint(it.t, it.mean, it.min, it.max) } }
    val description = stringResource(
        R.string.history_cd_line, title, stringResource(rangeDescription(state.range)),
        withUnit(line.min, unit, locale), withUnit(line.max, unit, locale),
    )
    HistoryLineChart(
        points = points,
        color = if (state.energy) OikosTheme.colors.solar else OikosTheme.colors.water,
        formatValue = { withUnit(it, unit, locale) },
        formatTime = formatTime,
        contentDescription = description,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Stat(R.string.history_min, withUnit(line.min, unit, locale))
        Stat(R.string.history_avg, withUnit(line.avg, unit, locale))
        Stat(R.string.history_max, withUnit(line.max, unit, locale))
    }
}

/** Device-class labels for binary sensors: door/window open vs. closed, motion/occupancy detected vs. clear, else on/off. */
internal fun binaryOnLabel(deviceClass: String?): Int = when (deviceClass) {
    "door", "window", "garage_door", "opening" -> R.string.history_binary_open
    "motion", "occupancy", "presence", "moving" -> R.string.history_binary_detected
    else -> R.string.history_binary_on
}

internal fun binaryOffLabel(deviceClass: String?): Int = when (deviceClass) {
    "door", "window", "garage_door", "opening" -> R.string.history_binary_closed
    "motion", "occupancy", "presence", "moving" -> R.string.history_binary_clear
    else -> R.string.history_binary_off
}

@Composable
private fun Stat(label: Int, value: String) {
    Column {
        Text(stringResource(label), style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
        Text(value, style = OikosTheme.type.cardValue)
    }
}

@Composable
private fun BarsBody(state: HistoryUi, bars: ChartContent.Bars, zone: ZoneId, locale: Locale) {
    val chartBars = remember(bars) { bars.days.days.map { ChartBar(it.t, it.kwh) } }
    val title = state.title ?: stringResource(DsR.string.energy_house)
    val total = format(bars.total, 1, locale)
    val avg = format(bars.avg, 1, locale)
    val description = stringResource(R.string.history_cd_bars, title, stringResource(rangeDescription(state.range)), total, avg)
    DailyBarsChart(
        bars = chartBars,
        average = bars.avg,
        color = OikosTheme.colors.solar,
        formatValue = { format(it, 1, locale) },
        formatDay = timeFormatter(HistoryRange.D30, zone, locale),
        contentDescription = description,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(stringResource(R.string.history_total, total, bars.days.days.size), style = OikosTheme.type.body)
        Text(stringResource(R.string.history_avg_per_day, avg), style = OikosTheme.type.body)
    }
}

private fun rangeLabel(range: HistoryRange) = when (range) {
    HistoryRange.H24 -> R.string.history_range_24h
    HistoryRange.D7 -> R.string.history_range_7d
    HistoryRange.D30 -> R.string.history_range_30d
}

private fun rangeDescription(range: HistoryRange) = when (range) {
    HistoryRange.H24 -> R.string.history_cd_range_24h
    HistoryRange.D7 -> R.string.history_cd_range_7d
    HistoryRange.D30 -> R.string.history_cd_range_30d
}

/** 24 h → `HH:mm`; 7 d → `EEE HH:mm`; 30 d and days → `d MMM`. */
private fun timeFormatter(range: HistoryRange, zone: ZoneId, locale: Locale): (Long) -> String {
    val pattern = when (range) {
        HistoryRange.H24 -> "HH:mm"
        HistoryRange.D7 -> "EEE HH:mm"
        HistoryRange.D30 -> "d MMM"
    }
    val formatter = DateTimeFormatter.ofPattern(pattern, locale).withZone(zone)
    return { seconds -> formatter.format(Instant.ofEpochSecond(seconds)) }
}

private fun format(value: Double, decimals: Int, locale: Locale) = String.format(locale, "%.${decimals}f", value)

/** One decimal, none for watts. */
private fun withUnit(value: Double, unit: String?, locale: Locale): String {
    val number = format(value, if (unit == "W") 0 else 1, locale)
    return if (unit.isNullOrBlank()) number else "$number $unit"
}
