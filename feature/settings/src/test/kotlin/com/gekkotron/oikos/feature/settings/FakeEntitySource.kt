package com.gekkotron.oikos.feature.settings

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

/** Mirrors MqttDiscoverySource.setAreas: optimistic update, rollback to the confirmed list on failure. */
class FakeEntitySource : EntitySource {
    override val link = MutableStateFlow<LinkState>(LinkState.Connected(TransportMode.DEMO))
    override val entities = MutableStateFlow<Map<String, Entity>>(emptyMap())
    override val devices = MutableStateFlow<Map<String, Device>>(emptyMap())
    override val areas = MutableStateFlow<List<Area>>(emptyList())
    override val shortcuts = MutableStateFlow<Map<String, com.gekkotron.oikos.voice.VoiceShortcut>>(emptyMap())
    override suspend fun saveShortcut(shortcut: com.gekkotron.oikos.voice.VoiceShortcut) {}
    override suspend fun deleteShortcut(id: String) {
        shortcuts.value = shortcuts.value - id
    }
    override val commandFailures = MutableSharedFlow<CommandFailure>()
    val published = mutableListOf<List<Area>>()
    var failWrites = false

    override fun states() = MutableStateFlow<Map<String, EntityState>>(emptyMap())
    override suspend fun send(uniqueId: String, command: Command): CommandResult = CommandResult.Accepted
    override suspend fun setOverride(uniqueId: String, override: UiOverride) = error("not used")
    override suspend fun setAreas(areas: List<Area>) {
        if (failWrites) throw PublishFailed("offline")
        published += areas
        this.areas.value = areas.sortedBy { it.order ?: Int.MAX_VALUE }
    }
}
