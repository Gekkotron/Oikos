package com.gekkotron.oikos.feature.energy.ui

import com.gekkotron.oikos.feature.energy.presentation.EnergyNode
import com.gekkotron.oikos.feature.energy.presentation.Flow
import com.gekkotron.oikos.feature.energy.presentation.NodeKind
import kotlin.math.log10

/** Layout B (spec §4): node centres as fractions of the diagram box, and the edge look. */
internal object FlowLayout {
    /** Width / height of the diagram box, as in the mockup's 260 × 250 viewBox. */
    const val ASPECT = 260f / 250f

    val SOLAR = 0.5f to 0.12f
    val HOUSE = 0.5f to 0.52f
    val GRID = 0.12f to 0.52f
    val BOTTOM_LEFT = 0.15f to 0.86f

    /** Arc slots right of the house, by how many consumer nodes are drawn (1..3). */
    private val ARC = mapOf(
        1 to listOf(0.86f to 0.52f),
        2 to listOf(0.86f to 0.34f, 0.86f to 0.72f),
        3 to listOf(0.82f to 0.2f, 0.88f to 0.52f, 0.82f to 0.84f),
    )

    fun positions(diagram: List<EnergyNode>): Map<String, Pair<Float, Float>> {
        val arc = diagram.filter { it.kind == NodeKind.CONSUMER || it.kind == NodeKind.MORE }
        val slots = ARC[arc.size.coerceIn(1, 3)].orEmpty()
        return buildMap {
            diagram.forEach { n ->
                when (n.kind) {
                    NodeKind.SOLAR -> put(n.id, SOLAR)
                    NodeKind.GRID -> put(n.id, GRID)
                    NodeKind.HOUSE -> put(n.id, HOUSE)
                    NodeKind.BATTERY, NodeKind.WATER -> put(n.id, BOTTOM_LEFT)
                    NodeKind.CONSUMER, NodeKind.MORE -> Unit
                }
            }
            arc.forEachIndexed { i, n -> slots.getOrNull(i)?.let { put(n.id, it) } }
        }
    }

    /** 0 at 10 W, 1 at 10 kW and above, on a log scale. */
    private fun level(watts: Double?): Float =
        if (watts == null || watts < 10.0) 0f else ((log10(watts) - 1.0) / 3.0).toFloat().coerceIn(0f, 1f)

    fun strokeDp(watts: Double?, flow: Flow): Float = if (flow == Flow.IDLE) 1f else 1f + 5f * level(watts)

    /**
     * Where an edge is in its dash cycle (0..1) after [seconds] of animation. Driven by a continuous clock, so
     * the dash never jumps at a fractional [speed] (a 0..1 phase that restarts each second would).
     */
    fun dashCycle(seconds: Double, speed: Float): Float = ((seconds * speed) % 1.0).toFloat()

    /** Dash cycles per second: 0.5 at 10 W to 2 at 10 kW. */
    fun speed(watts: Double?): Float = 0.5f + 1.5f * level(watts)
}
