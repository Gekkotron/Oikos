package com.gekkotron.oikos.designsystem.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val OikosIconSize: Dp = 24.dp

/**
 * A square canvas whose [draw] works in 24 grid units (device-icons spec §2), handed the neutral
 * color (the card's content color). A caller's size modifier wins over the 24 dp default.
 */
@Composable
internal fun IconCanvas(modifier: Modifier, draw: DrawScope.(neutral: Color) -> Unit) {
    val neutral = LocalContentColor.current
    Canvas(modifier.size(OikosIconSize)) {
        val s = size.minDimension / IconGeometry.GRID
        scale(s, s, pivot = Offset.Zero) { draw(neutral) }
    }
}

internal fun DrawScope.outline(path: Path, color: Color, width: Float = IconGeometry.STROKE) =
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))

/** A duotone surface: a soft fill plus its outline. */
internal fun DrawScope.surface(path: Path, color: Color, alpha: Float = IconGeometry.FILL_ALPHA) {
    drawPath(path, color, alpha = alpha)
    outline(path, color)
}

internal fun polygon(points: List<Offset>, close: Boolean = true) = Path().apply {
    moveTo(points[0].x, points[0].y)
    points.drop(1).forEach { lineTo(it.x, it.y) }
    if (close) close()
}
