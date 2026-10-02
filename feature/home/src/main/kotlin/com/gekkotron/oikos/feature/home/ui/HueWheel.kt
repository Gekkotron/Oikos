package com.gekkotron.oikos.feature.home.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.model.Hs
import kotlin.math.roundToInt

/** Sweep stops: a sweep gradient starts at 3 o'clock, the wheel's hue 0 is at 12, so stop i is hue 30·i + 90. */
/** How far from the marker a drag may start and still grab it. */
private val MARKER_GRAB = 32.dp

private val SWEEP = List(13) { i -> Color.hsv(((i * 30 + 90) % 360).toFloat(), 1f, 1f) }

/**
 * Hue around, saturation to the centre (LED spec §4.3). The marker follows [color]; a tap anywhere, or a drag
 * that starts on the marker, moves it locally and [onPick] fires once, on the tap or at the end of the drag.
 * A drag that starts elsewhere is left to the sheet, so scrolling across the wheel never picks a colour.
 */
@Composable
internal fun HueWheel(color: Hs?, onPick: (Hs) -> Unit, modifier: Modifier = Modifier) {
    var picked by remember { mutableStateOf(color ?: Hs(0.0, 0.0)) }
    LaunchedEffect(color) { if (color != null) picked = color }
    val pick by rememberUpdatedState(onPick)
    val description = stringResource(R.string.sheet_color)
    val state = stringResource(R.string.sheet_color_state, picked.hue.roundToInt() % 360, (picked.saturation * 100).roundToInt())
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, 280.dp)
        Canvas(
            Modifier
                .size(side)
                .semantics { contentDescription = description; stateDescription = state }
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        picked = WheelMath.hsAt(p.x, p.y, size.width.toFloat())
                        pick(picked)
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val (mx, my) = WheelMath.pointOf(picked, size.width.toFloat())
                        if ((down.position - Offset(mx, my)).getDistance() > MARKER_GRAB.toPx()) return@awaitEachGesture
                        down.consume()
                        val finished = drag(down.id) { change ->
                            picked = WheelMath.hsAt(change.position.x, change.position.y, size.width.toFloat())
                            change.consume()
                        }
                        if (finished) pick(picked)
                    }
                },
        ) {
            val radius = size.minDimension / 2f
            drawCircle(Brush.sweepGradient(SWEEP, center), radius)
            drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, radius), radius)
            val (x, y) = WheelMath.pointOf(picked, size.minDimension)
            drawCircle(Color.Black.copy(alpha = 0.35f), 13.5.dp.toPx(), Offset(x, y), style = Stroke(1.5.dp.toPx()))
            drawCircle(Color.White, 12.dp.toPx(), Offset(x, y), style = Stroke(3.dp.toPx()))
        }
    }
}
