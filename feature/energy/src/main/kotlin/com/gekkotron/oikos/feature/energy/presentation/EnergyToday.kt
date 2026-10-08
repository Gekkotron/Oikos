package com.gekkotron.oikos.feature.energy.presentation

import com.gekkotron.oikos.history.EnergyKind
import com.gekkotron.oikos.history.EnergyScale
import com.gekkotron.oikos.history.HistoryRef
import com.gekkotron.oikos.history.HistoryRefs
import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.Entity

/** What to ask the history service for the cards' "kWh today" (history spec §5.3). Pure; no Android types. */
object EnergyToday {
    /**
     * For every energy-role power entity: its meter's counter (when paired, recorded and in an energy unit), keyed by the
     * meter's id, else its own power integral keyed by its id. A derived house's export and inverter-draw counters
     * ([EnergyMeters.houseMeters]) are asked too, since its total subtracts them.
     */
    fun requests(entities: Map<String, Entity>): Map<String, Pair<HistoryRef, EnergyScale>> {
        val all = entities.values
        val out = linkedMapOf<String, Pair<HistoryRef, EnergyScale>>()
        val powers = all.filter { it.ui.energyRole?.power == true }
        powers.forEach { e ->
            val meter = EnergyMeters.meterFor(e, all)?.let { m -> request(m, EnergyKind.COUNTER)?.let { m.uniqueId to it } }
            (meter ?: request(e, EnergyKind.POWER)?.let { e.uniqueId to it })?.let { (id, r) -> out[id] = r }
        }
        val byRole = powers.groupBy { it.ui.energyRole!! }
        if (byRole[EnergyRole.HOUSE] == null) {
            EnergyMeters.houseMeters(byRole, all)?.minus?.forEach { m -> request(m, EnergyKind.COUNTER)?.let { out[m.uniqueId] = it } }
        }
        return out
    }

    private fun request(e: Entity, kind: EnergyKind): Pair<HistoryRef, EnergyScale>? {
        val ref = HistoryRefs.of(e) ?: return null
        val scale = EnergyScale.of(e.unit)?.takeIf { it.kind == kind } ?: return null
        return ref to scale
    }
}
