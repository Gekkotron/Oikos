package com.gekkotron.oikos.feature.energy.presentation

import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.transport.LinkState

enum class NodeKind { SOLAR, GRID, BATTERY, HOUSE, CONSUMER, MORE, WATER }

/** The direction of a node's edge, relative to the house (energy spec §4). */
enum class Flow { IN, OUT, IDLE }

/** Worst first is the higher ordinal: an aggregate takes the max. */
enum class NodeStatus { LIVE, STALE, UNAVAILABLE }

data class EnergyNode(
    /** "solar", "grid", "battery", "house", "water", "more", or a consumer's unique id. */
    val id: String,
    val kind: NodeKind,
    /** A consumer's name; null for role nodes, which the UI labels with the role name. */
    val name: String? = null,
    /** Watts, always ≥ 0 ([flow] carries the direction); water: its own unit. Null when unknown. */
    val value: Double?,
    /** Water only: the unit as reported. */
    val unit: String? = null,
    val flow: Flow = Flow.IDLE,
    val status: NodeStatus = NodeStatus.LIVE,
    /** [NodeKind.MORE] only: how many consumers it folds. */
    val count: Int = 0,
    /** The entities behind this node, by name; empty for a derived house. Keys of [EnergyUiState.sensors]. */
    val sensorIds: List<String> = emptyList(),
)

/** One tagged entity as the details sheet shows it: its own value and unit, not the node's watts. */
data class SensorUi(
    val id: String,
    val name: String,
    val value: Double?,
    val unit: String?,
    val status: NodeStatus,
    val role: EnergyRole,
    val invert: Boolean,
)

enum class EnergyHint { CHECK_SIGN }

/** The consumer order last shown, and when it was computed (re-ordered at most every 5 s, spec §4). */
data class ConsumerOrder(val ids: List<String>, val atMillis: Long)

data class EnergyUiState(
    /** Nothing discovered yet. */
    val loading: Boolean = true,
    /** Entities exist but none has an energy role. */
    val empty: Boolean = false,
    /** Drawn nodes: roles, up to 3 consumer slots (2 + MORE when folded), water when there is no battery. */
    val diagram: List<EnergyNode> = emptyList(),
    /** The cards under the diagram: role nodes, every consumer, water. */
    val cards: List<EnergyNode> = emptyList(),
    /** Every consumer in display order, for the "+N" sheet. */
    val consumers: List<EnergyNode> = emptyList(),
    /** 0..100, rounded down; null when hidden (spec §3). */
    val selfConsumption: Int? = null,
    val hint: EnergyHint? = null,
    val link: LinkState = LinkState.Connecting,
    val order: ConsumerOrder? = null,
    /** Every tagged entity, by unique id, for the details sheet. */
    val sensors: Map<String, SensorUi> = emptyMap(),
) {
    fun node(id: String): EnergyNode? = diagram.firstOrNull { it.id == id } ?: cards.firstOrNull { it.id == id }
}
