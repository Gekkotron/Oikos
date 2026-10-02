package com.gekkotron.oikos.feature.energy

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandFailure
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.Device
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Read-only fake: the Energy screen never sends commands or writes overrides. */
class FakeEntitySource : EntitySource {
    override val link = MutableStateFlow<LinkState>(LinkState.Connected(TransportMode.DEMO))
    override val entities = MutableStateFlow<Map<String, Entity>>(emptyMap())
    override val devices = MutableStateFlow<Map<String, Device>>(emptyMap())
    override val areas = MutableStateFlow<List<Area>>(emptyList())
    override val commandFailures = MutableSharedFlow<CommandFailure>()
    val stateMap = MutableStateFlow<Map<String, EntityState>>(emptyMap())

    override fun states() = stateMap
    override suspend fun send(uniqueId: String, command: Command): CommandResult = error("not used")
    override suspend fun setOverride(uniqueId: String, override: UiOverride) = error("not used")
    override suspend fun setAreas(areas: List<Area>) = error("not used")
}
