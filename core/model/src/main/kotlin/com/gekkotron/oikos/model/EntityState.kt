package com.gekkotron.oikos.model

import java.time.Instant

data class EntityState(
    val uniqueId: String,
    val value: StateValue,
    val attributes: Map<String, Any?> = emptyMap(),
    val available: Boolean = true,
    /** Time of the last confirmed state; null until the first message. */
    val lastUpdate: Instant? = null,
    /** True while an optimistic command waits for confirmation. */
    val pending: Boolean = false,
    /** True while the value comes from the entity cache and no live message has confirmed it. */
    val stale: Boolean = false,
    /** When a live message last changed the confirmed value; null until the app has seen a flip itself (last-change spec §4.3). */
    val changedAt: Instant? = null,
)
