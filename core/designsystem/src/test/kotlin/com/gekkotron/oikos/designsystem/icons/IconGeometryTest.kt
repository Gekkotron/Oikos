package com.gekkotron.oikos.designsystem.icons

import androidx.compose.ui.geometry.Offset
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IconGeometryTest {
    @Test
    fun `between interpolates every corner and clamps t`() {
        assertEquals(IconGeometry.DOOR_LEAF_CLOSED, IconGeometry.between(IconGeometry.DOOR_LEAF_CLOSED, IconGeometry.DOOR_LEAF_OPEN, 0f))
        assertEquals(IconGeometry.DOOR_LEAF_OPEN, IconGeometry.between(IconGeometry.DOOR_LEAF_CLOSED, IconGeometry.DOOR_LEAF_OPEN, 1f))
        assertEquals(IconGeometry.DOOR_LEAF_OPEN, IconGeometry.between(IconGeometry.DOOR_LEAF_CLOSED, IconGeometry.DOOR_LEAF_OPEN, 1.7f))
        assertEquals(Offset(15.75f, 4.25f), IconGeometry.between(IconGeometry.DOOR_LEAF_CLOSED, IconGeometry.DOOR_LEAF_OPEN, 0.5f)[1])
    }

    @Test
    fun `the door handle stays on the leaf at every step of the swing`() {
        for (step in 0..10) {
            val t = step / 10f
            val leaf = IconGeometry.between(IconGeometry.DOOR_LEAF_CLOSED, IconGeometry.DOOR_LEAF_OPEN, t)
            IconGeometry.between(IconGeometry.DOOR_HANDLE_CLOSED, IconGeometry.DOOR_HANDLE_OPEN, t).forEach { p ->
                assertTrue(inside(p, leaf), "handle point $p leaves the leaf at t=$t")
            }
        }
    }

    @Test
    fun `the open window sash grows past the frame toward the viewer`() {
        val open = IconGeometry.SASH_OPEN
        assertTrue(open.minOf { it.y } < 3f && open.maxOf { it.y } > 21f)
        assertEquals(4f, open.minOf { it.x }, "the hinge stays on the frame")
    }

    /** Convex polygon, corners in order: the point is on the same side of every edge. */
    private fun inside(p: Offset, poly: List<Offset>): Boolean {
        val signs = poly.indices.map { i ->
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
        }
        return signs.all { it >= 0f } || signs.all { it <= 0f }
    }
}
