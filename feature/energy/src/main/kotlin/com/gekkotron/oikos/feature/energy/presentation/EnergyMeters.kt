package com.gekkotron.oikos.feature.energy.presentation

import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.EnergyUnits
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.PowerUnits
import com.gekkotron.oikos.model.VolumeUnits

/** Pairs a power sensor with the lifetime energy counter (kWh) its card shows, and water with its meter index. Pure; no Android types. */
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
        // A clamp on the inverter line counts the production as "produced"; its plain counter is the standby draw.
        if (power.ui.energyRole == EnergyRole.SOLAR) twin(power, counters, PRODUCED)?.let { return it }
        val wanted = normalize(labelOf(power)).replace("power", "energy")
        counters.firstOrNull { normalize(labelOf(it)) == wanted }?.let { return it }
        twin(power, counters, "energy")?.let { return it }
        val powers = onDevice.filter { PowerUnits.factor(it.unit) != null }
        return if (counters.size == 1 && powers.size == 1) counters.single() else null
    }

    /** Every entity reporting a water volume unit, for the water meter picker. */
    fun volumes(entities: Collection<Entity>): List<Entity> = entities.filter { VolumeUnits.isVolume(it.unit) }

    /** The meter index for a [water] sensor: the user's explicit choice, else [waterAuto]. A dangling explicit choice pairs nothing. */
    fun waterMeterFor(water: Entity, entities: Collection<Entity>): Entity? {
        val chosen = water.ui.energyMeter ?: return waterAuto(water, entities)
        return entities.firstOrNull { it.uniqueId == chosen && it.uniqueId != water.uniqueId && VolumeUnits.isVolume(it.unit) }
    }

    /**
     * What automatic pairing picks for [water]: another sensor on its device in a meter-scale unit (m³, ft³, CCF; Suez's
     * "Water meter reading"), unless [water] is itself in one, being the meter. Same-unit pairs (L and L, gal and gal)
     * can't be told apart from a day's usage, so they need the explicit choice.
     */
    fun waterAuto(water: Entity, entities: Collection<Entity>): Entity? {
        val device = water.deviceId ?: return null
        if (VolumeUnits.isMeterScale(water.unit)) return null
        return entities.filter { it.deviceId == device && it.uniqueId != water.uniqueId && VolumeUnits.isMeterScale(it.unit) }
            .minByOrNull { it.uniqueId }
    }

    /**
     * The counter of the opposite direction on the same device, subtracted from a derived house: a grid sensor's
     * export (`…_power_a_…` ↔ `…_energy_produced_a_…`), or the inverter's draw when [meterFor] picked a solar
     * sensor's production twin (`…_energy_b_…`). Null for any other role or pairing.
     */
    fun reverseFor(power: Entity, entities: Collection<Entity>): Entity? {
        val device = power.deviceId ?: return null
        val counters = entities.filter { it.deviceId == device && EnergyUnits.factor(it.unit) != null }
        return when (power.ui.energyRole) {
            EnergyRole.GRID -> twin(power, counters, PRODUCED)
            EnergyRole.SOLAR -> twin(power, counters, "energy").takeIf { meterFor(power, entities) == twin(power, counters, PRODUCED) }
            else -> null
        }
    }

    private fun twin(power: Entity, counters: List<Entity>, replacement: String): Entity? {
        val wanted = power.uniqueId.replace(POWER_TOKEN, replacement)
        return if (wanted == power.uniqueId) null else counters.firstOrNull { it.uniqueId.equals(wanted, ignoreCase = true) }
    }

    private const val PRODUCED = "energy_produced"

    /** "power" as a word of an id ("_power_a_"), not inside one ("powers/…"). */
    private val POWER_TOKEN = Regex("(?<![a-z0-9])power(?![a-z])", RegexOption.IGNORE_CASE)

    private fun labelOf(e: Entity) = e.label ?: e.name

    private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
