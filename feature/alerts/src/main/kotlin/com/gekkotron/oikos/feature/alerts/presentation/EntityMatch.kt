package com.gekkotron.oikos.feature.alerts.presentation

import com.gekkotron.oikos.model.Entity

/** Finds the entity an alert's `entity_id` points at, for "Open device" (push alerts spec §7.3). */
object EntityMatch {
    /** The unique id of the match, or null. Tries the exact id, the part after the dot, then name slugs. */
    fun find(entityId: String?, entities: Map<String, Entity>): String? {
        val wanted = entityId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (wanted in entities) return wanted
        val objectId = wanted.substringAfter('.', missingDelimiterValue = wanted)
        if (objectId in entities) return objectId
        val slug = slug(objectId)
        return entities.values.firstOrNull { slug(it.name) == slug || slug(it.uniqueId) == slug }?.uniqueId
    }

    private fun slug(text: String): String = text.lowercase().replace(NOT_ALNUM, "_").trim('_')

    private val NOT_ALNUM = Regex("[^a-z0-9]+")
}
