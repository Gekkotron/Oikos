package com.gekkotron.oikos.feature.home

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
import com.gekkotron.oikos.transport.PublishFailed
import com.gekkotron.oikos.transport.TransportMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeEntitySource : EntitySource {
    override val link = MutableStateFlow<LinkState>(LinkState.Connected(TransportMode.DEMO))
    override val entities = MutableStateFlow<Map<String, Entity>>(emptyMap())
    override val devices = MutableStateFlow<Map<String, Device>>(emptyMap())
    override val areas = MutableStateFlow<List<Area>>(emptyList())
    override val commandFailures = MutableSharedFlow<CommandFailure>(extraBufferCapacity = 8)
    val stateMap = MutableStateFlow<Map<String, EntityState>>(emptyMap())
    val sent = mutableListOf<Pair<String, Command>>()
    var result: CommandResult = CommandResult.Accepted

    /** Every override written, last one per id. */
    val overrides = mutableMapOf<String, UiOverride>()
    val areaWrites = mutableListOf<List<Area>>()

    /** "areas" or "override", in call order, whether or not the write succeeded. */
    val calls = mutableListOf<String>()

    /** Makes every override and areas write fail like an offline broker. */
    var failWrites = false

    override fun states() = stateMap

    override suspend fun send(uniqueId: String, command: Command): CommandResult {
        sent += uniqueId to command
        return result
    }

    override suspend fun setOverride(uniqueId: String, override: UiOverride) {
        calls += "override"
        if (failWrites) throw PublishFailed("offline")
        overrides[uniqueId] = override
        entities.value[uniqueId]?.let { e ->
            entities.value += uniqueId to e.copy(ui = override, area = override.area ?: e.area, name = override.name ?: e.name)
        }
    }

    override suspend fun setAreas(areas: List<Area>) {
        calls += "areas"
        if (failWrites) throw PublishFailed("offline")
        areaWrites += areas
        this.areas.value = areas
    }
}
