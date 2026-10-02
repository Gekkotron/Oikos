package com.gekkotron.oikos.feature.energy.ui

import com.gekkotron.oikos.feature.energy.presentation.EnergyNode
import com.gekkotron.oikos.feature.energy.presentation.Flow
import com.gekkotron.oikos.feature.energy.presentation.NodeKind
import androidx.compose.ui.geometry.Offset
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlowLayoutTest {
    private fun node(id: String, kind: NodeKind) = EnergyNode(id, kind, value = 0.0)

    @Test
    fun `fixed slots for the role nodes`() {
        val p = FlowLayout.positions(listOf(node("solar", NodeKind.SOLAR), node("grid", NodeKind.GRID), node("house", NodeKind.HOUSE), node("water", NodeKind.WATER)))
        assertEquals(FlowLayout.SOLAR, p["solar"])
        assertEquals(FlowLayout.GRID, p["grid"])
        assertEquals(FlowLayout.HOUSE, p["house"])
        assertEquals(FlowLayout.BOTTOM_LEFT, p["water"])
    }

    @Test
    fun `battery takes the bottom left slot`() {
        val p = FlowLayout.positions(listOf(node("house", NodeKind.HOUSE), node("battery", NodeKind.BATTERY)))
        assertEquals(FlowLayout.BOTTOM_LEFT, p["battery"])
    }

    @Test
    fun `consumers spread on the arc from top to bottom`() {
        val p = FlowLayout.positions(listOf(node("house", NodeKind.HOUSE), node("a", NodeKind.CONSUMER), node("b", NodeKind.CONSUMER), node("more", NodeKind.MORE)))
        val ys = listOf("a", "b", "more").map { p.getValue(it).second }
        assertEquals(ys.sorted(), ys)
        assertTrue(listOf("a", "b", "more").all { p.getValue(it).first > FlowLayout.HOUSE.first })
    }

    @Test
    fun `stroke grows with power on a log scale and idle is thin`() {
        assertEquals(1f, FlowLayout.strokeDp(5.0, Flow.IDLE))
        assertEquals(1f, FlowLayout.strokeDp(10.0, Flow.IN))
        assertEquals(6f, FlowLayout.strokeDp(10_000.0, Flow.IN))
        assertEquals(6f, FlowLayout.strokeDp(50_000.0, Flow.OUT))
        val mid = FlowLayout.strokeDp(1_000.0, Flow.IN)
        assertTrue(mid > 1f && mid < 6f)
    }

    @Test
    fun `the dash cycle is continuous at any speed`() {
        for (speed in listOf(0.5f, 1.5f, 1.77f, 2f)) {
            for (t in listOf(1.0, 7.0, 60.0, 3_600.0)) {
                val before = FlowLayout.dashCycle(t - 0.001, speed)
                val after = FlowLayout.dashCycle(t + 0.001, speed)
                val step = ((after - before) % 1f + 1f) % 1f
                assertTrue(step < 0.01f, "speed $speed jumps by $step at t=$t")
            }
        }
    }

    private val hub = Offset(100f, 100f)

    @Test
    fun `a side edge runs from the node's facing side to the house's, at circle-centre height`() {
        val e = FlowLayout.edge(node = Offset(20f, 150f), hub = hub, clearance = 25f)
        assertEquals(Offset(45f, 150f), e.start)
        assertEquals(Offset(75f, 100f), e.end)
        // Leaves and arrives horizontally, so it never dips into the labels under a circle.
        assertEquals(e.start.y, e.c1.y)
        assertEquals(e.end.y, e.c2.y)
        val right = FlowLayout.edge(node = Offset(180f, 40f), hub = hub, clearance = 25f)
        assertEquals(Offset(155f, 40f), right.start)
        assertEquals(Offset(125f, 100f), right.end)
    }

    @Test
    fun `a solar edge drops from below its labels to the top of the house`() {
        val e = FlowLayout.edge(node = Offset(100f, 20f), hub = hub, clearance = 25f, below = 60f)
        assertEquals(Offset(100f, 60f), e.start)
        assertEquals(Offset(100f, 75f), e.end)
    }

    @Test
    fun `reversing keeps the shape and swaps the ends`() {
        val e = FlowLayout.edge(node = Offset(20f, 150f), hub = hub, clearance = 25f)
        val r = e.reversed()
        assertEquals(listOf(e.end, e.c2, e.c1, e.start), listOf(r.start, r.c1, r.c2, r.end))
    }
}
