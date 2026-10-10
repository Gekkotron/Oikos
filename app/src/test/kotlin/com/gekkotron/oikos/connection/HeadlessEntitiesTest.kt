package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.EntityCache
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.data.RoutePreference
import com.gekkotron.oikos.feature.widget.CatalogResult
import com.gekkotron.oikos.feature.widget.Failure
import com.gekkotron.oikos.feature.widget.RefreshResult
import com.gekkotron.oikos.feature.widget.SendResult
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
import com.gekkotron.oikos.transport.TransportSpec
import com.gekkotron.oikos.transport.testing.FakeManagedTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class HeadlessEntitiesTest {
    private val switchId = "sw1"
    private val configTopic = "homeassistant/switch/sw1/config"
    private val config = """{"command_topic":"sw/1/set","state_topic":"sw/1","unique_id":"$switchId","name":"Kettle"}"""
    private val profile = ConnectionProfile.Direct(LocalBroker("broker.test"))

    private class MemoryCache(var stored: Map<String, String> = emptyMap()) : EntityCache {
        var saves = 0
        override suspend fun load(key: String) = stored
        override suspend fun save(key: String, messages: Map<String, String>) {
            stored = messages
            saves++
        }
        override suspend fun clear() {
            stored = emptyMap()
        }
    }

    private class Harness(
        val entities: HeadlessEntities,
        val transports: MutableList<FakeManagedTransport>,
        val closedBeforeNext: MutableList<Boolean>,
        val cache: MemoryCache,
    )

    private fun TestScope.harness(
        state: ProfileState = ProfileState.Ready(profile),
        live: EntitySource? = null,
        cache: MemoryCache = MemoryCache(),
        connect: (FakeManagedTransport) -> Unit = { t ->
            t.fake.retain(configTopic, config)
            t.fake.retain("sw/1", "ON")
            t.fake.setLink(LinkState.Connected(TransportMode.DIRECT))
        },
    ): Harness {
        val made = mutableListOf<FakeManagedTransport>()
        val closedBeforeNext = mutableListOf<Boolean>()
        val entities = HeadlessEntities(
            profile = { state },
            route = { RoutePreference.AUTO },
            installId = { "abcd-ef01-2345" },
            live = { live },
            foreground = { false },
            cache = cache,
            transports = { spec, _ ->
                made.lastOrNull()?.let { closedBeforeNext += it.closed }
                FakeManagedTransport(spec).also {
                    made += it
                    connect(it)
                }
            },
            context = StandardTestDispatcher(testScheduler),
        )
        return Harness(entities, made, closedBeforeNext, cache)
    }

    @Test
    fun refresh_connects_reads_and_closes() = runTest {
        val h = harness()
        val result = h.entities.refresh(setOf(switchId)) as RefreshResult.Ok
        assertEquals(StateValue.OnOff(true), result.states[switchId]?.value)
        assertTrue(result.complete)
        val transport = h.transports.single()
        assertTrue(transport.closed)
        assertTrue((transport.spec as TransportSpec.Direct).clientId.endsWith("-widget"))
    }

    @Test
    fun no_profile_is_not_configured_and_never_connects() = runTest {
        val h = harness(state = ProfileState.None)
        assertEquals(RefreshResult.Failed(Failure.NOT_CONFIGURED), h.entities.refresh(setOf(switchId)))
        assertTrue(h.transports.isEmpty())
    }

    @Test
    fun rejected_credentials_are_auth_failed() = runTest {
        val h = harness(connect = { it.fake.setLink(LinkState.Offline(LinkState.Offline.REJECTED)) })
        assertEquals(RefreshResult.Failed(Failure.AUTH_FAILED), h.entities.refresh(setOf(switchId)))
        assertTrue(h.transports.single().closed)
    }

    @Test
    fun silent_broker_is_offline_after_connect_budget() = runTest {
        val h = harness(connect = { it.fake.setLink(LinkState.Connecting) })
        assertEquals(RefreshResult.Failed(Failure.OFFLINE), h.entities.refresh(setOf(switchId)))
        assertTrue(h.transports.single().closed)
    }

    @Test
    fun foreground_source_is_used_without_a_new_connection() = runTest {
        val h = harness(live = FakeLiveSource(entity(switchId)))
        val result = h.entities.refresh(setOf(switchId)) as RefreshResult.Ok
        assertEquals(StateValue.OnOff(true), result.states[switchId]?.value)
        assertTrue(h.transports.isEmpty())
    }

    @Test
    fun unsettled_session_never_overwrites_the_cache() = runTest {
        // Connected, but the broker replays nothing: only the cached (stale) house exists, so nothing may be saved.
        val cache = MemoryCache(mapOf(configTopic to config, "sw/1" to "OFF"))
        val h = harness(cache = cache, connect = { it.fake.setLink(LinkState.Connected(TransportMode.DIRECT)) })
        val result = h.entities.refresh(setOf(switchId)) as RefreshResult.Ok
        assertEquals(false, result.complete)
        assertEquals(0, cache.saves)
    }

    @Test
    fun settled_session_saves_the_cache() = runTest {
        val h = harness()
        h.entities.refresh(setOf(switchId))
        assertEquals(1, h.cache.saves)
        assertEquals("ON", h.cache.stored["sw/1"])
    }

    @Test
    fun send_publishes_and_confirms_on_state_echo() = runTest {
        val h = harness(connect = { t ->
            t.fake.retain(configTopic, config)
            t.fake.retain("sw/1", "ON")
            t.fake.setLink(LinkState.Connected(TransportMode.DIRECT))
            // Echo each command back as the new state, like a real device.
            backgroundScope.launch {
                var seen = 0
                while (true) {
                    val new = t.fake.published.drop(seen)
                    seen += new.size
                    new.filter { it.topic == "sw/1/set" }.forEach { t.fake.deliver("sw/1", it.payloadString()) }
                    delay(50)
                }
            }
        })
        val result = h.entities.send(switchId, Command.TurnOff)
        assertTrue("$result", result is SendResult.Confirmed)
        assertEquals(StateValue.OnOff(false), (result as SendResult.Confirmed).state.value)
        assertTrue(h.transports.single().closed)
    }

    @Test
    fun send_without_echo_is_not_confirmed() = runTest {
        val h = harness()
        assertEquals(SendResult.NotConfirmed, h.entities.send(switchId, Command.TurnOff))
        assertEquals(listOf("sw/1/set" to "OFF"), h.transports.single().fake.publishedStrings())
    }

    @Test
    fun send_to_unknown_entity_is_not_confirmed() = runTest {
        val h = harness()
        assertEquals(SendResult.NotConfirmed, h.entities.send("nope", Command.TurnOff))
    }

    @Test
    fun sessions_never_overlap() = runTest {
        val h = harness()
        val a = async { h.entities.refresh(setOf(switchId)) }
        val b = async { h.entities.refresh(setOf(switchId)) }
        a.await()
        b.await()
        assertEquals(2, h.transports.size)
        assertEquals(listOf(true), h.closedBeforeNext)
    }

    @Test
    fun catalog_offline_falls_back_to_cache() = runTest {
        val cache = MemoryCache(mapOf(configTopic to config))
        val h = harness(cache = cache, connect = { it.fake.setLink(LinkState.Offline(LinkState.Offline.FAILED)) })
        val result = h.entities.catalog() as CatalogResult.Ok
        assertEquals(listOf(switchId), result.entities.map { it.uniqueId })
    }

    @Test
    fun catalog_without_profile_is_not_configured() = runTest {
        assertEquals(CatalogResult.Failed(Failure.NOT_CONFIGURED), harness(state = ProfileState.None).entities.catalog())
    }

    @Test
    fun a_button_press_is_confirmed_without_waiting_for_a_state() = runTest {
        val h = harness(connect = { t ->
            t.fake.retain("homeassistant/button/b1/config", """{"command_topic":"b/1/press","unique_id":"b1","name":"Bell"}""")
            t.fake.setLink(LinkState.Connected(TransportMode.DIRECT))
        })
        val result = h.entities.send("b1", Command.Press)
        assertTrue("$result", result is SendResult.Confirmed)
        assertTrue("took ${testScheduler.currentTime} ms", testScheduler.currentTime < 8_000)
        assertEquals("b/1/press", h.transports.single().fake.publishedStrings().single().first)
    }

    @Test
    fun a_live_source_that_has_not_settled_is_not_complete() = runTest {
        // Connected but no replay yet: a missing id must not be declared removed.
        val live = com.gekkotron.oikos.source.MqttDiscoverySource(com.gekkotron.oikos.transport.testing.FakeMqttTransport(), backgroundScope)
        val h = harness(live = live)
        val result = h.entities.refresh(setOf(switchId)) as RefreshResult.Ok
        assertEquals(false, result.complete)
        assertTrue(h.transports.isEmpty())
    }

    /** Zigbee2MQTT does not retain device states by default: the broker replays the config, never the state. */
    private fun TestScope.nonRetainedHarness(echo: Boolean) = harness(connect = { t ->
        t.fake.retain(configTopic, config)
        t.fake.setLink(LinkState.Connected(TransportMode.DIRECT))
        if (echo) {
            backgroundScope.launch {
                var seen = 0
                while (true) {
                    val new = t.fake.published.drop(seen)
                    seen += new.size
                    new.filter { it.topic == "sw/1/set" }.forEach { t.fake.deliver("sw/1", it.payloadString()) }
                    delay(50)
                }
            }
        }
    })

    @Test
    fun a_tap_on_a_non_retained_state_sends_once_discovery_settles_not_after_8_s() = runTest {
        val h = nonRetainedHarness(echo = true)
        val result = h.entities.send(switchId, Command.TurnOn())
        assertTrue("$result", result is SendResult.Confirmed)
        assertTrue("took ${testScheduler.currentTime} ms", testScheduler.currentTime < 5_000)
    }

    @Test
    fun a_refresh_with_non_retained_states_returns_once_discovery_settles() = runTest {
        val h = nonRetainedHarness(echo = false)
        val result = h.entities.refresh(setOf(switchId)) as RefreshResult.Ok
        assertTrue(result.complete)
        assertTrue("took ${testScheduler.currentTime} ms", testScheduler.currentTime < 5_000)
    }

    private object NoBindings : Bindings

    private fun entity(id: String) = Entity(id, null, "Kettle", Kind.SWITCH, null, null, null, Capabilities(), NoBindings)

    /** A connected foreground source holding one switch that is on. */
    private class FakeLiveSource(entity: Entity) : EntitySource {
        override val link: StateFlow<LinkState> = MutableStateFlow(LinkState.Connected(TransportMode.DIRECT))
        override val entities: StateFlow<Map<String, Entity>> = MutableStateFlow(mapOf(entity.uniqueId to entity))
        override val devices: StateFlow<Map<String, Device>> = MutableStateFlow(emptyMap())
        override val areas: StateFlow<List<Area>> = MutableStateFlow(emptyList())
        override val shortcuts: kotlinx.coroutines.flow.StateFlow<Map<String, com.gekkotron.oikos.voice.VoiceShortcut>> =
            kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
        override suspend fun saveShortcut(shortcut: com.gekkotron.oikos.voice.VoiceShortcut) {}
        override suspend fun deleteShortcut(id: String) {}
        override val commandFailures: SharedFlow<CommandFailure> = MutableSharedFlow()
        private val states = mapOf(entity.uniqueId to EntityState(entity.uniqueId, StateValue.OnOff(true), lastUpdate = Instant.EPOCH))
        override fun states(): Flow<Map<String, EntityState>> = flowOf(states)
        override suspend fun send(uniqueId: String, command: Command): CommandResult = CommandResult.Accepted
        override suspend fun setOverride(uniqueId: String, override: UiOverride) {}
        override suspend fun setAreas(areas: List<Area>) {}
    }
}