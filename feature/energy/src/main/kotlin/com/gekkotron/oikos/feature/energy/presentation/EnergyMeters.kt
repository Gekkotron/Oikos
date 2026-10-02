package com.gekkotron.oikos.feature.energy.presentation

import com.gekkotron.oikos.model.EnergyUnits
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.PowerUnits

/** Pairs a power sensor with the lifetime energy counter (kWh) its card shows. Pure; no Android types. */
object EnergyMeters {
    /** Every entity reporting an energy unit. */
    fun counters(entities: Collection<Entity>): List<Entity> = entities.filter { EnergyUnits.factor(it.unit) != null }

    /** The counter for [power]: the user's explicit choice, else [auto]. A dangling explicit choice pairs nothing. */
    fun meterFor(power: Entity, entities: Collection<Entity>): Entity? {
        val chosen = power.ui.energyMeter ?: return auto(power, entities)
        return entities.firstOrNull { it.uniqueId == chosen && EnergyUnits.factor(it.unit) != null }
    }

    /**
     * What automatic pairing picks, ignoring the override: a counter on the same device whose label is [power]'s with
     * "power" read as "energy", else the device's only counter when [power] is also its only power entity.
     */
    fun auto(power: Entity, entities: Collection<Entity>): Entity? {
        val device = power.deviceId ?: return null
        val onDevice = entities.filter { it.deviceId == device }
        val counters = onDevice.filter { EnergyUnits.factor(it.unit) != null }
        val wanted = normalize(labelOf(power)).replace("power", "energy")
        counters.firstOrNull { normalize(labelOf(it)) == wanted }?.let { return it }
        val powers = onDevice.filter { PowerUnits.factor(it.unit) != null }
        return if (counters.size == 1 && powers.size == 1) counters.single() else null
    }

    private fun labelOf(e: Entity) = e.label ?: e.name

    private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
