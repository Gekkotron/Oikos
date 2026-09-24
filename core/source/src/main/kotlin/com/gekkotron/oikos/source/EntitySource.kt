package com.gekkotron.oikos.source

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandFailure
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.Device
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.transport.LinkState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Where entities come from. v1: MQTT discovery; later: Home Assistant WebSocket (spec §4.3). */
interface EntitySource {
    /** Broker connection, for the status pill. */
    val link: StateFlow<LinkState>

    /** After overrides are applied (name, area id, ui). */
    val entities: StateFlow<Map<String, Entity>>
    val devices: StateFlow<Map<String, Device>>

    /** Declared areas (by order, then name), then implicit ones (by name), then Unassigned if used. */
    val areas: StateFlow<List<Area>>

    /** Optimistic commands that were not confirmed in time (snackbar "Not confirmed — Retry"). */
    val commandFailures: SharedFlow<CommandFailure>

    fun states(): Flow<Map<String, EntityState>>

    suspend fun send(uniqueId: String, command: Command): CommandResult

    suspend fun setOverride(uniqueId: String, override: UiOverride)

    suspend fun setAreas(areas: List<Area>)
}
