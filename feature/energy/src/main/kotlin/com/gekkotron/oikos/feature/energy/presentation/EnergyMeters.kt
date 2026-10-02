package com.gekkotron.oikos.feature.energy.presentation

import com.gekkotron.oikos.model.EnergyRole
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
     * "power" read as "energy", else one whose unique id is (Z2M names `power_a` and `energy_a` after the device alone,
     * so only `…_power_a_…` ↔ `…_energy_a_…` tells them apart), else the device's only counter when [power] is also
     * its only power entity.
     */
    fun auto(power: Entity, entities: Collection<Entity>): Entity? {
        val device = power.deviceId ?: return null
        val onDevice = entities.filter { it.deviceId == device }
        val counters = onDevice.filter { EnergyUnits.factor(it.unit) != null }
        val wanted = normalize(labelOf(power)).replace("power", "energy")
        counters.firstOrNull { normalize(labelOf(it)) == wanted }?.let { return it }
        twin(power, counters, "energy")?.let { return it }
        val powers = onDevice.filter { PowerUnits.factor(it.unit) != null }
        return if (counters.size == 1 && powers.size == 1) counters.single() else null
    }

    /**
     * A grid sensor's export counter on the same device, the unique-id twin of its import counter (a bidirectional
     * Z2M clamp: `…_power_a_…` ↔ `…_energy_produced_a_…`); null for any other role.
     */
    fun exportFor(power: Entity, entities: Collection<Entity>): Entity? {
        if (power.ui.energyRole != EnergyRole.GRID) return null
        val device = power.deviceId ?: return null
        return twin(power, entities.filter { it.deviceId == device && EnergyUnits.factor(it.unit) != null }, "energy_produced")
    }

    private fun twin(power: Entity, counters: List<Entity>, replacement: String): Entity? {
        val wanted = power.uniqueId.replace(POWER_TOKEN, replacement)
        return if (wanted == power.uniqueId) null else counters.firstOrNull { it.uniqueId.equals(wanted, ignoreCase = true) }
    }

    /** "power" as a word of an id ("_power_a_"), not inside one ("powers/…"). */
    private val POWER_TOKEN = Regex("(?<![a-z0-9])power(?![a-z])", RegexOption.IGNORE_CASE)

    private fun labelOf(e: Entity) = e.label ?: e.name

    private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
