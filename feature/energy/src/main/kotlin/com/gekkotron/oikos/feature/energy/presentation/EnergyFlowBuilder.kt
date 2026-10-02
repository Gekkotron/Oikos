package com.gekkotron.oikos.feature.energy.presentation

import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.PowerUnits
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.transport.LinkState
import kotlin.math.abs
import kotlin.math.max

/** Entities with an energy role → the Energy screen (energy spec §2–§4). Pure; no Android types. */
object EnergyFlowBuilder {
    const val REORDER_MS = 5_000L
    const val IDLE_W = 10.0
    const val SELF_CONSUMPTION_MIN_W = 50.0
    const val ARC_SLOTS = 3

    /** Consumer node ids never collide with the fixed role ids ("house", "solar"…), whatever the entity's unique id. */
    const val CONSUMER_PREFIX = "consumer:"

    /** One role's value in watts (null = unknown) and its worst status. */
    private data class Reading(val watts: Double?, val status: NodeStatus)

    fun build(
        entities: Map<String, Entity>,
        states: Map<String, EntityState>,
        link: LinkState,
        nowMillis: Long,
        previous: ConsumerOrder? = null,
    ): EnergyUiState {
        if (entities.isEmpty()) return EnergyUiState(loading = true, link = link)
        val byRole = entities.values.filter { it.ui.energyRole != null }.groupBy { it.ui.energyRole!! }
        if (byRole.isEmpty()) return EnergyUiState(loading = false, empty = true, link = link)

        val solar = sum(byRole[EnergyRole.SOLAR], states)?.let { it.copy(watts = it.watts?.coerceAtLeast(0.0)) }
        val grid = grid(sum(byRole[EnergyRole.GRID], states), sum(byRole[EnergyRole.GRID_EXPORT], states))
        val battery = sum(byRole[EnergyRole.BATTERY], states)
        val consumerEntities = byRole[EnergyRole.CONSUMER].orEmpty()

        var hint: EnergyHint? = null
        val measured = sum(byRole[EnergyRole.HOUSE], states)
        val house: Reading? = when {
            measured != null -> measured
            else -> {
                val inputs = listOfNotNull(solar, grid, battery)
                when {
                    inputs.isNotEmpty() -> {
                        val total = if (inputs.any { it.watts == null }) null else inputs.sumOf { it.watts!! }
                        if (total != null && total < 0) hint = EnergyHint.CHECK_SIGN
                        // An unavailable input (an inverter offline at night) leaves the house unknown, not
                        // unavailable: the house itself still has power.
                        Reading(total?.coerceAtLeast(0.0), worst(inputs.map { if (it.status == NodeStatus.UNAVAILABLE) NodeStatus.LIVE else it.status }))
                    }
                    consumerEntities.isNotEmpty() -> Reading(null, NodeStatus.LIVE)
                    else -> null
                }
            }
        }

        val consumerNodes = consumerEntities.associate { e ->
            val r = sum(listOf(e), states)!!
            val w = r.watts?.coerceAtLeast(0.0)
            e.uniqueId to EnergyNode(CONSUMER_PREFIX + e.uniqueId, NodeKind.CONSUMER, name = e.name, value = w, flow = outward(w), status = r.status)
        }
        val fresh = rank(consumerEntities, consumerNodes)
        val order = if (previous != null && nowMillis - previous.atMillis < REORDER_MS && previous.ids.toSet() == fresh.toSet()) {
            previous
        } else {
            ConsumerOrder(fresh, nowMillis)
        }
        val consumers = order.ids.map(consumerNodes::getValue)
        val arc = if (consumers.size <= ARC_SLOTS) consumers else consumers.take(ARC_SLOTS - 1) + more(consumers.drop(ARC_SLOTS - 1))

        val solarNode = solar?.let { EnergyNode("solar", NodeKind.SOLAR, value = it.watts, flow = inward(it.watts), status = it.status) }
        val gridNode = grid?.let { EnergyNode("grid", NodeKind.GRID, value = it.watts?.let(::abs), flow = signed(it.watts), status = it.status) }
        val batteryNode = battery?.let { EnergyNode("battery", NodeKind.BATTERY, value = it.watts?.let(::abs), flow = signed(it.watts), status = it.status) }
        val houseNode = house?.let { EnergyNode("house", NodeKind.HOUSE, value = it.watts, status = it.status) }
        val waterNode = byRole[EnergyRole.WATER]?.firstOrNull()?.let { e -> water(e, states[e.uniqueId]) }

        val roles = listOfNotNull(solarNode, gridNode, batteryNode, houseNode)
        val s = solar?.watts
        val selfConsumption = if (s != null && s >= SELF_CONSUMPTION_MIN_W && (grid == null || grid.watts != null)) {
            val export = max(0.0, -(grid?.watts ?: 0.0))
            ((s - export) * 100 / s).toInt().coerceIn(0, 100)
        } else {
            null
        }
        return EnergyUiState(
            loading = false,
            empty = false,
            diagram = roles + arc + listOfNotNull(waterNode.takeIf { batteryNode == null }),
            cards = roles + consumers + listOfNotNull(waterNode),
            consumers = consumers,
            selfConsumption = selfConsumption,
            hint = hint,
            link = link,
            order = order,
        )
    }

    /** Summed watts of [entities] (null if any is unknown), or null when the role has no entity. */
    private fun sum(entities: List<Entity>?, states: Map<String, EntityState>): Reading? {
        if (entities.isNullOrEmpty()) return null
        val parts = entities.map { e -> reading(e, states[e.uniqueId]) }
        val watts = if (parts.any { it.watts == null }) null else parts.sumOf { it.watts!! }
        return Reading(watts, worst(parts.map { it.status }))
    }

    private fun reading(e: Entity, state: EntityState?): Reading {
        val status = status(state)
        val numeric = (state?.value as? StateValue.Numeric)?.value
        val factor = PowerUnits.factor(e.unit)
        val sign = if (e.ui.energyInvert && e.ui.energyRole?.signed == true) -1.0 else 1.0
        val watts = if (status == NodeStatus.UNAVAILABLE || numeric == null || factor == null) null else numeric * factor * sign
        return Reading(watts, status)
    }

    private fun status(state: EntityState?): NodeStatus = when {
        state?.available == false -> NodeStatus.UNAVAILABLE
        state?.stale == true -> NodeStatus.STALE
        else -> NodeStatus.LIVE
    }

    private fun worst(statuses: List<NodeStatus>): NodeStatus = statuses.maxByOrNull { it.ordinal } ?: NodeStatus.LIVE

    /** G = Σgrid − Σgrid_export (spec §3). */
    private fun grid(import: Reading?, export: Reading?): Reading? = when {
        import == null && export == null -> null
        export == null -> import
        import == null -> Reading(export.watts?.let { -it }, export.status)
        else -> Reading(
            if (import.watts == null || export.watts == null) null else import.watts - export.watts,
            worst(listOf(import.status, export.status)),
        )
    }

    private fun inward(w: Double?): Flow = if (w != null && w >= IDLE_W) Flow.IN else Flow.IDLE

    private fun outward(w: Double?): Flow = if (w != null && w >= IDLE_W) Flow.OUT else Flow.IDLE

    /** + flows into the house (import, discharge), − flows out (export, charge). */
    private fun signed(w: Double?): Flow = when {
        w == null -> Flow.IDLE
        w >= IDLE_W -> Flow.IN
        w <= -IDLE_W -> Flow.OUT
        else -> Flow.IDLE
    }

    /** `UiOverride.order` first, then power high → low (unknown last), then name. */
    private fun rank(entities: List<Entity>, nodes: Map<String, EnergyNode>): List<String> =
        entities.sortedWith(
            compareBy<Entity>({ it.ui.order ?: Int.MAX_VALUE }, { -(nodes.getValue(it.uniqueId).value ?: -1.0) }, { it.name }),
        ).map { it.uniqueId }

    private fun more(rest: List<EnergyNode>): EnergyNode {
        val total = if (rest.any { it.value == null }) null else rest.sumOf { it.value!! }
        return EnergyNode("more", NodeKind.MORE, value = total, flow = outward(total), status = worst(rest.map { it.status }), count = rest.size)
    }

    private fun water(e: Entity, state: EntityState?): EnergyNode {
        val status = status(state)
        val value = (state?.value as? StateValue.Numeric)?.value.takeIf { status != NodeStatus.UNAVAILABLE }
        return EnergyNode("water", NodeKind.WATER, value = value, unit = e.unit, status = status)
    }
}
