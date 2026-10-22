package com.gekkotron.oikos.history

import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind

/** The hub records each entity under its `unique_id`; these helpers pick which kinds qualify. */
object HistoryRefs {
    fun of(entity: Entity): HistoryRef? = when (entity.kind) {
        Kind.SENSOR -> if (entity.unit.isNullOrBlank()) null else HistoryRef(entity.uniqueId)
        Kind.CLIMATE, Kind.BINARY_SENSOR -> HistoryRef(entity.uniqueId)
        else -> null
    }

    /**
     * A binary sensor's recorded series, read for the time of its last flip (last-change spec §4.1). Narrower
     * than [of]: Security's "since X" line only wants binary refs, not the full chart-eligible set.
     */
    fun changeRef(entity: Entity): HistoryRef? =
        if (entity.kind == Kind.BINARY_SENSOR) HistoryRef(entity.uniqueId) else null
}
