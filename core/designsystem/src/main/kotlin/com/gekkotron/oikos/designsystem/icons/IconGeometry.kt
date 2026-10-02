package com.gekkotron.oikos.designsystem.icons

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp

/** Device icon geometry on a 24-unit grid (device-icons spec §2). */
internal object IconGeometry {
    const val GRID = 24f
    const val STROKE = 1.6f
    const val FILL_ALPHA = 0.22f
    const val BOX_ALPHA = 0.35f
    const val GLASS_ALPHA = 0.10f

    /** Shutter curtain travel: the opening is `y 6.5–21`. */
    const val SHUTTER_TRAVEL = 14.5f

    /** Garage curtain travel: the opening is `y 11–21`. */
    const val GARAGE_TRAVEL = 10f

    val DOOR_LEAF_CLOSED = listOf(Offset(6f, 3f), Offset(18f, 3f), Offset(18f, 21f), Offset(6f, 21f))
    val DOOR_LEAF_OPEN = listOf(Offset(6f, 3f), Offset(13.5f, 5.5f), Offset(13.5f, 21.5f), Offset(6f, 21f))

    /** Both ends sit at fixed positions on the leaf (80 % / 63 % across, 55 % down), so the handle never leaves it. */
    val DOOR_HANDLE_CLOSED = listOf(Offset(15.6f, 12.9f), Offset(13.6f, 12.9f))
    val DOOR_HANDLE_OPEN = listOf(Offset(12f, 14f), Offset(10.75f, 13.77f))

    /** The left sash swings inward: its free edge comes toward the viewer and grows past the frame. */
    val SASH_CLOSED = listOf(Offset(4f, 3f), Offset(12f, 3f), Offset(12f, 21f), Offset(4f, 21f))
    val SASH_OPEN = listOf(Offset(4f, 3f), Offset(8.5f, 1f), Offset(8.5f, 23f), Offset(4f, 21f))

    fun between(a: List<Offset>, b: List<Offset>, t: Float): List<Offset> {
        val f = t.coerceIn(0f, 1f)
        return a.zip(b) { p, q -> lerp(p, q, f) }
    }
}
