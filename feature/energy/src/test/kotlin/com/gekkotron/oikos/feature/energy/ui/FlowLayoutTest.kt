package com.gekkotron.oikos.feature.energy.ui

import com.gekkotron.oikos.feature.energy.presentation.EnergyNode
import com.gekkotron.oikos.feature.energy.presentation.Flow
import com.gekkotron.oikos.feature.energy.presentation.NodeKind
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
}
