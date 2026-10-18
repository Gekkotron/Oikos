package com.gekkotron.oikos.source

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Diagnostic
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.MqttMessage
import com.gekkotron.oikos.transport.MqttTransport
import com.gekkotron.oikos.transport.TransportMode
import com.gekkotron.oikos.transport.testing.FakeMqttTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regression tests for the final whole-branch review findings. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewFixesTest {
    private fun TestScope.start(transport: FakeMqttTransport, diagnostics: MutableList<Diagnostic> = mutableListOf()): MqttDiscoverySource {
        val source = MqttDiscoverySource(transport, backgroundScope, diagnostics = { diagnostics += it })
        runCurrent()
        return source
    }

    private fun TestScope.settle() {
        advanceTimeBy(MqttDiscoverySource.SUBSCRIPTION_DEBOUNCE.inWholeMilliseconds + 1)
        runCurrent()
    }

    // I1: a climate entity without temperature_state_topic is optimistic for its setpoint.
    @Test
    fun `setpoint without temperature state topic is optimistic and survives room readings`() = runTest {
        val t = FakeMqttTransport()
        val source = start(t)
        t.deliver(
            "homeassistant/climate/th/config",
            """{"unique_id":"th","current_temperature_topic":"th/cur","temperature_command_topic":"th/set"}""",
        )
        runCurrent(); settle()
        t.deliver("th/cur", "22")
        runCurrent()

        source.send("th", Command.SetTemperature(21.0))
        val afterSend = source.states().first().getValue("th")
        assertFalse(afterSend.pending)
        t.deliver("th/cur", "22.5")
        runCurrent()
        assertEquals(StateValue.Climate(null, 21.0, 22.5), source.states().first().getValue("th").value)
    }

    // I1: with a setpoint state topic, only that topic confirms the setpoint.
    @Test
    fun `room reading does not confirm a pending setpoint`() = runTest {
        val t = FakeMqttTransport()
        val source = start(t)
        t.deliver(
            "homeassistant/climate/th/config",
            """{"unique_id":"th","current_temperature_topic":"th/cur","temperature_state_topic":"th/target","temperature_command_topic":"th/set"}""",
        )
        runCurrent(); settle()
        t.deliver("th/target", "19"); t.deliver("th/cur", "22")
        runCurrent()

        source.send("th", Command.SetTemperature(21.0))
        t.deliver("th/cur", "22.5")
        runCurrent()
        var s = source.states().first().getValue("th")
        assertTrue(s.pending)
        assertEquals(21.0, (s.value as StateValue.Climate).target)

        t.deliver("th/target", "21")
        runCurrent()
        s = source.states().first().getValue("th")
        assertFalse(s.pending)
        assertEquals(StateValue.Climate(null, 21.0, 22.5), s.value)
    }

    // I2: an optimistic cover settles to open/closed instead of "opening" forever.
    @Test
    fun `optimistic cover settles to its final state`() = runTest {
        val t = FakeMqttTransport()
        val source = start(t)
        t.deliver("homeassistant/cover/rf/config", """{"unique_id":"rf","command_topic":"rf/set","set_position_topic":"rf/pos"}""")
        runCurrent()
        source.send("rf", Command.Open)
        assertEquals(StateValue.Cover(CoverState.OPEN, 100), source.states().first().getValue("rf").value)
        source.send("rf", Command.Close)
        assertEquals(StateValue.Cover(CoverState.CLOSED, 0), source.states().first().getValue("rf").value)
        source.send("rf", Command.SetPosition(40))
        assertEquals(StateValue.Cover(CoverState.OPEN, 40), source.states().first().getValue("rf").value)
    }

    // I4: remove + re-add inside the debounce window keeps the last state.
    @Test
    fun `remove then re-add within the debounce keeps the last state`() = runTest {
        val t = FakeMqttTransport()
        val source = start(t)
        val config = """{"unique_id":"a","command_topic":"sw/a/set","state_topic":"sw/a"}"""
        t.deliver("homeassistant/switch/a/config", config)
        runCurrent(); settle()
        t.deliver("sw/a", "ON")
        runCurrent()

        t.deliver("homeassistant/switch/a/config", "")
        runCurrent()
        advanceTimeBy(100)
        t.deliver("homeassistant/switch/a/config", config)
        runCurrent(); settle()
        assertEquals(StateValue.OnOff(true), source.states().first().getValue("a").value)
    }

    // Upgraded minor 1: a malformed update keeps the previously working entity (HA behaviour).
    @Test
    fun `malformed update keeps the existing entity`() = runTest {
        val t = FakeMqttTransport()
        val diagnostics = mutableListOf<Diagnostic>()
        val source = start(t, diagnostics)
        t.deliver("homeassistant/switch/a/config", """{"unique_id":"a","command_topic":"sw/a/set"}""")
        runCurrent()
        t.deliver("homeassistant/switch/a/config", "{broken")
        runCurrent()
        assertEquals(setOf("a"), source.entities.value.keys)
        assertEquals(1, diagnostics.size)
    }

    // I3: a stale snapshot must never be written after a newer one (multi-threaded dispatcher).
    @Test
    fun `concurrent overrides never drop a freshly discovered entity`() = runBlocking {
        repeat(300) { round ->
            val transport = ThreadSafeTransport()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val source = MqttDiscoverySource(transport, scope)
                transport.emit("homeassistant/switch/base/config", """{"unique_id":"base","command_topic":"b/set"}""")
                withTimeout(5_000) { while ("base" !in source.entities.value) yield() }
                val writer = scope.launch { repeat(50) { source.setOverride("base", UiOverride(name = "n$it")) } }
                transport.emit("homeassistant/switch/new/config", """{"unique_id":"new","command_topic":"n/set"}""")
                writer.join()
                // A lost entity never comes back (no further events), so a bounded wait is a real check.
                val seen = withTimeoutOrNull(1_000) { while ("new" !in source.entities.value) yield(); true }
                assertTrue(seen == true, "round $round lost the new entity")
            } finally {
                scope.cancel()
            }
        }
    }

    /** Minimal thread-safe transport: hands messages to the source, drops publishes. */
    private class ThreadSafeTransport : MqttTransport {
        override val link: StateFlow<LinkState> = MutableStateFlow(LinkState.Connected(TransportMode.DIRECT))
        private val flow = MutableSharedFlow<MqttMessage>(extraBufferCapacity = 10_000)

        override fun messages(): SharedFlow<MqttMessage> = flow
        override suspend fun setSubscriptions(filters: Set<String>) = Unit
        override suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean) = Unit

        suspend fun emit(topic: String, payload: String) =
            flow.emit(MqttMessage(topic, payload.encodeToByteArray(), retained = false))
    }
}
