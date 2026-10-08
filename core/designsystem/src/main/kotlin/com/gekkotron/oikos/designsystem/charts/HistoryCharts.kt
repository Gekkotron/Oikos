package com.gekkotron.oikos.designsystem.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosTheme
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisTickComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.continuous
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.common.shape.rounded
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.LineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.core.common.shape.CorneredShape

/** One history sample: `t` is unix seconds. */
data class ChartPoint(val t: Long, val mean: Double, val min: Double, val max: Double)

/** One daily bar: `t` is unix seconds. */
data class ChartBar(val t: Long, val value: Double)

private val ChartHeight = 220.dp
private const val AxisLabelCount = 4

@Composable
fun HistoryLineChart(
    points: List<ChartPoint>,
    color: Color,
    formatValue: (Double) -> String,
    formatTime: (Long) -> String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val frame = modifier.fillMaxWidth().height(ChartHeight)
        .semantics { this.contentDescription = contentDescription }
    if (points.isEmpty()) {
        Box(frame)
        return
    }
    val first = points.first().t
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(points) {
        val xs = points.map { (it.t - first).toDouble() }
        producer.runTransaction {
            lineSeries {
                series(xs, points.map { it.min })
                series(xs, points.map { it.max })
                series(xs, points.map { it.mean })
            }
        }
    }
    val faint = color.copy(alpha = 0.25f)
    val minLine = LineCartesianLayer.rememberLineFor(faint, 1f)
    val maxLine = LineCartesianLayer.rememberLineFor(faint, 1f)
    val meanLine = LineCartesianLayer.rememberLineFor(color, 2f)
    val step = remember(points) { xStep(points.map { it.t - first }) }
    val spacing = remember(points) {
        val span = points.last().t - first
        maxOf(1, (span / step / AxisLabelCount).toInt())
    }
    val marker = rememberDefaultCartesianMarker(
        label = rememberTextComponent(color = OikosTheme.colors.content),
        valueFormatter = DefaultCartesianMarker.ValueFormatter { _, targets ->
            val point = (targets.firstOrNull() as? LineCartesianLayerMarkerTarget)?.points?.lastOrNull()
            if (point == null) "" else {
                val t = first + point.entry.x.toLong()
                "${formatTime(t)}  ${formatValue(point.entry.y)}"
            }
        },
    )
    val axisLine = OikosTheme.colors.contentMuted.copy(alpha = 0.3f)
    val chart = rememberCartesianChart(
        rememberLineCartesianLayer(
            lineProvider = LineCartesianLayer.LineProvider.series(minLine, maxLine, meanLine),
        ),
        startAxis = VerticalAxis.rememberStart(
            label = rememberAxisLabelComponent(color = OikosTheme.colors.contentMuted),
            line = rememberAxisLineComponent(fill = fill(axisLine)),
            tick = rememberAxisTickComponent(fill = fill(axisLine)),
            guideline = rememberAxisGuidelineComponent(fill = fill(axisLine)),
            valueFormatter = CartesianValueFormatter { _, value, _ -> formatValue(value) },
        ),
        bottomAxis = HorizontalAxis.rememberBottom(
            label = rememberAxisLabelComponent(color = OikosTheme.colors.contentMuted),
            line = rememberAxisLineComponent(fill = fill(axisLine)),
            tick = rememberAxisTickComponent(fill = fill(axisLine)),
            guideline = rememberAxisGuidelineComponent(fill = fill(axisLine)),
            valueFormatter = CartesianValueFormatter { _, value, _ -> formatTime(first + value.toLong()) },
            itemPlacer = HorizontalAxis.ItemPlacer.aligned(spacing = { spacing }),
        ),
        marker = marker,
    )
    // Keyed on the data: a new range or mode starts again from the whole span.
    key(points) {
        CartesianChartHost(
            chart = chart,
            modelProducer = producer,
            modifier = frame,
            scrollState = rememberVicoScrollState(scrollEnabled = true),
            zoomState = rememberHistoryZoom(points.last().t - first, step),
        )
    }
}

@Composable
fun DailyBarsChart(
    bars: List<ChartBar>,
    color: Color,
    formatValue: (Double) -> String,
    formatDay: (Long) -> String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val frame = modifier.fillMaxWidth().height(ChartHeight)
        .semantics { this.contentDescription = contentDescription }
    if (bars.isEmpty()) {
        Box(frame)
        return
    }
    val first = bars.first().t
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(bars) {
        val xs = bars.map { (it.t - first).toDouble() }
        producer.runTransaction { columnSeries { series(xs, bars.map { it.value }) } }
    }
    val step = remember(bars) { xStep(bars.map { it.t - first }) }
    val spacing = remember(bars) {
        maxOf(1, ((bars.last().t - first) / step / AxisLabelCount).toInt())
    }
    val axisLine = OikosTheme.colors.contentMuted.copy(alpha = 0.3f)
    val chart = rememberCartesianChart(
        rememberColumnCartesianLayer(
            columnProvider = ColumnCartesianLayer.ColumnProvider.series(
                rememberLineComponent(
                    fill = fill(color),
                    thickness = 8.dp,
                    shape = CorneredShape.rounded(2.dp),
                ),
            ),
        ),
        startAxis = VerticalAxis.rememberStart(
            label = rememberAxisLabelComponent(color = OikosTheme.colors.contentMuted),
            line = rememberAxisLineComponent(fill = fill(axisLine)),
            tick = rememberAxisTickComponent(fill = fill(axisLine)),
            guideline = rememberAxisGuidelineComponent(fill = fill(axisLine)),
            valueFormatter = CartesianValueFormatter { _, value, _ -> formatValue(value) },
        ),
        bottomAxis = HorizontalAxis.rememberBottom(
            label = rememberAxisLabelComponent(color = OikosTheme.colors.contentMuted),
            line = rememberAxisLineComponent(fill = fill(axisLine)),
            tick = rememberAxisTickComponent(fill = fill(axisLine)),
            guideline = rememberAxisGuidelineComponent(fill = fill(axisLine)),
            valueFormatter = CartesianValueFormatter { _, value, _ -> formatDay(first + value.toLong()) },
            itemPlacer = HorizontalAxis.ItemPlacer.aligned(spacing = { spacing }),
        ),
    )
    key(bars) {
        CartesianChartHost(
            chart = chart,
            modelProducer = producer,
            modifier = frame,
            scrollState = rememberVicoScrollState(scrollEnabled = true),
            zoomState = rememberHistoryZoom(bars.last().t - first, step),
        )
    }
}

@Composable
fun ChartSkeleton(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(ChartHeight)
            .background(OikosTheme.colors.card, RoundedCornerShape(12.dp)),
    )
}

/** Pinch-zoom and pan: opens on the whole span, never zooms out past it, nor in past [maxZoomSpan]. */
@Composable
private fun rememberHistoryZoom(span: Long, step: Long) = rememberVicoZoomState(
    zoomEnabled = true,
    initialZoom = Zoom.Content,
    minZoom = Zoom.Content,
    maxZoom = Zoom.max(Zoom.x(maxZoomSpan(span, step)), Zoom.Content),
)

/** The narrowest x-span (seconds) zooming may show: a tenth of the data, but never fewer than four samples. */
internal fun maxZoomSpan(span: Long, step: Long): Double = maxOf(span / 10.0, 4.0 * step)

@Composable
private fun LineCartesianLayer.Companion.rememberLineFor(color: Color, thicknessDp: Float) =
    LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(fill(color)),
        stroke = LineCartesianLayer.LineStroke.continuous(thickness = thicknessDp.dp),
    )

/** Vico spaces x in multiples of the gcd of the deltas; labels are placed in those units. */
private fun xStep(offsets: List<Long>): Long {
    var g = 0L
    for (i in 1 until offsets.size) g = gcd(g, offsets[i] - offsets[i - 1])
    return if (g <= 0L) 1L else g
}

private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
