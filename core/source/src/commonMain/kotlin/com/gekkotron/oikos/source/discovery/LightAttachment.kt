package com.gekkotron.oikos.source.discovery

import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind

/**
 * Folds a device's number and select entities into its light (LED control spec §4.1): a strip's
 * "Effect speed" or "Palette" belongs in the light's sheet, not on its own card. Only a device with
 * exactly one light folds; with two, which light a number drives is ambiguous.
 */
object LightAttachment {
    private val FOLDED = setOf(Kind.NUMBER, Kind.SELECT)

    /** uniqueId -> owning light's uniqueId, for every number or select folded into a light. */
    fun compute(entities: Collection<Entity>): Map<String, String> {
        val lightOf = entities.filter { it.kind == Kind.LIGHT && it.deviceId != null }
            .groupBy { it.deviceId!! }
            .mapNotNull { (device, lights) -> lights.singleOrNull()?.let { device to it.uniqueId } }
            .toMap()
        if (lightOf.isEmpty()) return emptyMap()
        return entities.filter { it.kind in FOLDED }
            .mapNotNull { e -> e.deviceId?.let(lightOf::get)?.let { e.uniqueId to it } }
            .toMap()
    }
}
