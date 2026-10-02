package com.gekkotron.oikos.designsystem.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import com.gekkotron.oikos.designsystem.icons.IconGeometry.BOX_ALPHA
import com.gekkotron.oikos.designsystem.icons.IconGeometry.GARAGE_TRAVEL
import com.gekkotron.oikos.designsystem.icons.IconGeometry.GLASS_ALPHA
import com.gekkotron.oikos.designsystem.icons.IconGeometry.SHUTTER_TRAVEL
import com.gekkotron.oikos.designsystem.icons.IconGeometry.between

/** A roller shutter (spec §2.1): the curtain is drawn closed and raised by [openFraction] (0 closed, 1 open). */
@Composable
fun ShutterIcon(openFraction: Float, modifier: Modifier = Modifier) = IconCanvas(modifier) { neutral ->
    val f = openFraction.coerceIn(0f, 1f)
    outline(Path().apply { addRoundRect(RoundRect(3.5f, 3f, 20.5f, 21f, CornerRadius(2f))) }, neutral)
    clipRect(3.5f, 6.5f, 20.5f, 21f) {
        translate(top = -SHUTTER_TRAVEL * f) {
            curtain(neutral, left = 3.5f, top = 6.5f, right = 20.5f, slats = listOf(9f, 11.5f, 14f, 16.5f, 19f))
        }
    }
    // Above the curtain, so a raised curtain disappears into the box.
    val box = RoundRect(3.5f, 3f, 20.5f, 6.5f, topLeftCornerRadius = CornerRadius(2f), topRightCornerRadius = CornerRadius(2f))
    surface(Path().apply { addRoundRect(box) }, neutral, BOX_ALPHA)
}

/** A rolling garage door (spec §2.4), open/closed accent on the curtain. */
@Composable
fun GarageIcon(openness: Float, accent: Color? = null, modifier: Modifier = Modifier) = IconCanvas(modifier) { neutral ->
    val f = openness.coerceIn(0f, 1f)
    outline(polygon(listOf(Offset(3f, 21f), Offset(3f, 9f), Offset(12f, 4f), Offset(21f, 9f), Offset(21f, 21f)), close = false), neutral)
    clipRect(6f, 11f, 18f, 21f) {
        translate(top = -GARAGE_TRAVEL * f) {
            curtain(accent ?: neutral, left = 6f, top = 11f, right = 18f, slats = listOf(13.5f, 16f, 18.5f))
        }
    }
    drawRect(neutral, Offset(6f, 9.5f), Size(12f, 1.5f), alpha = BOX_ALPHA)
}

/** A door seen from inside (spec §2.2): the leaf and its lever handle swing together by [openness]. */
@Composable
fun DoorIcon(openness: Float, accent: Color? = null, modifier: Modifier = Modifier) = IconCanvas(modifier) { neutral ->
    outline(polygon(listOf(Offset(6f, 21f), Offset(6f, 3f), Offset(18f, 3f), Offset(18f, 21f)), close = false), neutral)
    outline(polygon(listOf(Offset(4f, 21f), Offset(20f, 21f)), close = false), neutral)
    surface(polygon(between(IconGeometry.DOOR_LEAF_CLOSED, IconGeometry.DOOR_LEAF_OPEN, openness)), accent ?: neutral)
    val handle = between(IconGeometry.DOOR_HANDLE_CLOSED, IconGeometry.DOOR_HANDLE_OPEN, openness)
    drawLine(neutral, handle[0], handle[1], strokeWidth = 1.9f, cap = StrokeCap.Round)
}

/** A French window (spec §2.3): the left sash swings inward by [openness]; the right pane is fixed. */
@Composable
fun WindowIcon(openness: Float, accent: Color? = null, modifier: Modifier = Modifier) = IconCanvas(modifier) { neutral ->
    outline(Path().apply { addRoundRect(RoundRect(4f, 3f, 20f, 21f, CornerRadius(1.5f))) }, neutral)
    drawRect(neutral, Offset(12f, 3f), Size(8f, 18f), alpha = GLASS_ALPHA)
    outline(polygon(listOf(Offset(12f, 12f), Offset(20f, 12f)), close = false), neutral)
    surface(polygon(between(IconGeometry.SASH_CLOSED, IconGeometry.SASH_OPEN, openness)), accent ?: neutral)
}

/** A closed curtain filling `left..right × top..21`: fill, slat lines, and a bottom rail. */
private fun DrawScope.curtain(color: Color, left: Float, top: Float, right: Float, slats: List<Float>) {
    drawRect(color, Offset(left, top), Size(right - left, 21f - top), alpha = IconGeometry.FILL_ALPHA)
    slats.forEach { y -> drawLine(color, Offset(left, y), Offset(right, y), strokeWidth = 1f, alpha = 0.75f) }
    drawLine(color, Offset(left + 0.7f, 20.6f), Offset(right - 0.7f, 20.6f), strokeWidth = 1.8f, cap = StrokeCap.Round)
}
