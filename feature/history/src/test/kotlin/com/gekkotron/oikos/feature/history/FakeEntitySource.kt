package com.gekkotron.oikos.feature.history

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

/** The Energy screen only reads, and writes overrides from its details sheet. */
class FakeEntitySource : EntitySource {
    override val link = MutableStateFlow<LinkState>(LinkState.Connected(TransportMode.DEMO))
    override val entities = MutableStateFlow<Map<String, Entity>>(emptyMap())
    override val devices = MutableStateFlow<Map<String, Device>>(emptyMap())
    override val areas = MutableStateFlow<List<Area>>(emptyList())
    override val shortcuts: kotlinx.coroutines.flow.StateFlow<Map<String, com.gekkotron.oikos.voice.VoiceShortcut>> =
        kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
    override suspend fun saveShortcut(shortcut: com.gekkotron.oikos.voice.VoiceShortcut) {}
    override suspend fun deleteShortcut(id: String) {}
    override val commandFailures = MutableSharedFlow<CommandFailure>()
    val stateMap = MutableStateFlow<Map<String, EntityState>>(emptyMap())

    override fun states() = stateMap
    override suspend fun send(uniqueId: String, command: Command): CommandResult = error("not used")
    val overrides = mutableMapOf<String, UiOverride>()

    /** Makes every override write fail like an offline broker. */
    var failWrites = false

    override suspend fun setOverride(uniqueId: String, override: UiOverride) {
        if (failWrites) throw PublishFailed("offline")
        overrides[uniqueId] = override
    }
    override suspend fun setAreas(areas: List<Area>) = error("not used")
}
