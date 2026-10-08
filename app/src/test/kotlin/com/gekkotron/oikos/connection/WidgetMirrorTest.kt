package com.gekkotron.oikos.connection

import com.gekkotron.oikos.feature.widget.model.LinkStatus
import com.gekkotron.oikos.feature.widget.model.TileValue
import com.gekkotron.oikos.feature.widget.model.WidgetConfig
import com.gekkotron.oikos.feature.widget.model.WidgetSnapshot
import com.gekkotron.oikos.feature.widget.store.WidgetConfigStore
import com.gekkotron.oikos.feature.widget.store.WidgetSnapshotStore
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Bindings
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandFailure
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.Device
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetMirrorTest {
    private object B : Bindings

    private fun entity(id: String, name: String) = Entity(id, null, name, Kind.SWITCH, null, null, null, Capabilities(), B)

    private class MemConfigs(map: Map<Int, WidgetConfig> = emptyMap()) : WidgetConfigStore {
        override val configs = MutableStateFlow(map)
        override suspend fun get(appWidgetId: Int) = configs.value[appWidgetId]
        override suspend fun put(appWidgetId: Int, config: WidgetConfig) {
            configs.value = configs.value + (appWidgetId to config)
        }
        override suspend fun remove(appWidgetIds: IntArray) {
            configs.value = configs.value - appWidgetIds.toSet()
        }
        override suspend fun allIds() = configs.value.values.flatMap { it.uniqueIds }.toSet()
    }

    private class MemSnapshots : WidgetSnapshotStore {
        val flow = MutableStateFlow(WidgetSnapshot())
        override val snapshot: Flow<WidgetSnapshot> = flow
        override suspend fun current() = flow.value
        override suspend fun update(transform: (WidgetSnapshot) -> WidgetSnapshot) {
            flow.value = transform(flow.value)
        }
        override suspend fun clear() {
            flow.value = WidgetSnapshot()
        }
    }

    /** A connected foreground source whose states the test drives. */
    private class LiveSource(entities: List<Entity>) : EntitySource {
        private val states = MutableStateFlow<Map<String, EntityState>>(emptyMap())
        override val link: StateFlow<LinkState> = MutableStateFlow(LinkState.Connected(TransportMode.DIRECT))
        override val entities: StateFlow<Map<String, Entity>> = MutableStateFlow(entities.associateBy { it.uniqueId })
        override val devices: StateFlow<Map<String, Device>> = MutableStateFlow(emptyMap())
        override val areas: StateFlow<List<Area>> = MutableStateFlow(emptyList())
        override val commandFailures: SharedFlow<CommandFailure> = MutableSharedFlow()
        override fun states(): Flow<Map<String, EntityState>> = states
        override suspend fun send(uniqueId: String, command: Command): CommandResult = CommandResult.Accepted
        override suspend fun setOverride(uniqueId: String, override: UiOverride) {}
        override suspend fun setAreas(areas: List<Area>) {}

        fun emit(id: String, value: StateValue) {
            states.value = states.value + (id to EntityState(id, value, lastUpdate = Instant.ofEpochMilli(1_000)))
        }
    }

    @Test
    fun mirrors_only_configured_ids_debounced() = runTest {
        val source = LiveSource(listOf(entity("s", "Kettle"), entity("x", "Other")))
        val snaps = MemSnapshots()
        var updates = 0
        WidgetMirror(flowOf(source), MemConfigs(mapOf(1 to WidgetConfig(listOf("s")))), snaps, { updates++ }, { false }, backgroundScope, now = { 5L }).start()
        runCurrent()
        source.emit("s", StateValue.OnOff(true))
        source.emit("x", StateValue.OnOff(true))
        source.emit("s", StateValue.OnOff(false))
        advanceTimeBy(500)
        runCurrent()
        assertEquals(0, updates)
        advanceTimeBy(600)
        runCurrent()
        assertEquals(1, updates)
        assertEquals(setOf("s"), snaps.flow.value.tiles.keys)
        assertEquals(TileValue.OnOff(false), snaps.flow.value.tiles["s"]?.value)
        assertEquals(LinkStatus.FRESH, snaps.flow.value.link)
        assertEquals(5L, snaps.flow.value.lastFresh)
    }

    @Test
    fun no_source_means_no_writes() = runTest {
        val snaps = MemSnapshots()
        var updates = 0
        WidgetMirror(flowOf(null), MemConfigs(mapOf(1 to WidgetConfig(listOf("s")))), snaps, { updates++ }, { false }, backgroundScope).start()
        advanceUntilIdle()
        assertEquals(0, updates)
        assertEquals(WidgetSnapshot(), snaps.flow.value)
    }

    @Test
    fun no_widgets_means_no_writes() = runTest {
        val source = LiveSource(listOf(entity("s", "Kettle")))
        var updates = 0
        WidgetMirror(flowOf(source), MemConfigs(), MemSnapshots(), { updates++ }, { false }, backgroundScope).start()
        source.emit("s", StateValue.OnOff(true))
        advanceUntilIdle()
        assertEquals(0, updates)
    }

    @Test
    fun never_declares_removed() = runTest {
        val source = LiveSource(emptyList())
        val snaps = MemSnapshots()
        WidgetMirror(flowOf(source), MemConfigs(mapOf(1 to WidgetConfig(listOf("gone")))), snaps, {}, { false }, backgroundScope).start()
        source.emit("other", StateValue.OnOff(true))
        advanceUntilIdle()
        assertEquals(null, snaps.flow.value.tiles["gone"])
    }
}
