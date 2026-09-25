package com.gekkotron.oikos.source

import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.model.UiOverrides
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode
import com.gekkotron.oikos.transport.testing.FakeMqttTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Entity cache (spec 2026-09-25): journal, restore, prune and settle. */
@OptIn(ExperimentalCoroutinesApi::class)
class EntityCacheSourceTest {
    private val lightTopic = "homeassistant/light/0x01/light/config"
    private val lightId = "0x01_light_zigbee2mqtt"
    private val lightConfig =
        """{"schema":"json","command_topic":"z2m/lampe/set","state_topic":"z2m/lampe","brightness":true,"unique_id":"$lightId","name":null,"device":{"identifiers":["z_lampe"],"name":"Lampe","suggested_area":"Salon"}}"""
    private val cached = mapOf(
        lightTopic to lightConfig,
        "z2m/lampe" to """{"state":"ON","brightness":254}""",
        "oikos/ui/$lightId" to UiOverrides.encode(UiOverride(name = "Plafonnier")),
    )

    private fun offline() = FakeMqttTransport().apply { setLink(LinkState.Offline("test")) }

    /** Offline by default, like a cold start away from home. */
    private fun TestScope.start(transport: FakeMqttTransport = offline()) =
        transport to MqttDiscoverySource(transport, backgroundScope).also { runCurrent() }

    private fun TestScope.debounce() {
        advanceTimeBy(MqttDiscoverySource.SUBSCRIPTION_DEBOUNCE.inWholeMilliseconds + 1)
        runCurrent()
    }

    @Test
    fun `restore builds the house with stale states while offline`() = runTest {
        val (_, source) = start()
        source.restore(cached)
        runCurrent()
        val light = source.entities.value.getValue(lightId)
        assertEquals("Plafonnier", light.name)
        assertEquals("Salon", light.area)
        val state = source.states().first().getValue(lightId)
        assertEquals(true, (state.value as StateValue.Light).on)
        assertTrue(state.stale)
    }

    @Test
    fun `restore replays configs before states whatever the map order`() = runTest {
        val (_, source) = start()
        source.restore(linkedMapOf("z2m/lampe" to """{"state":"ON"}""", lightTopic to lightConfig))
        runCurrent()
        assertTrue(source.states().first().getValue(lightId).stale)
    }

    @Test
    fun `live data wins over a restore that finishes later`() = runTest {
        val transport = offline()
        transport.deliver(lightTopic, lightConfig, retain = true)
        val (_, source) = start(transport)
        debounce()
        transport.deliver("z2m/lampe", """{"state":"OFF"}""")
        runCurrent()

        source.restore(cached)
        runCurrent()
        val state = source.states().first().getValue(lightId)
        assertEquals(false, (state.value as StateValue.Light).on)
        assertFalse(state.stale)
    }

    @Test
    fun `the journal holds the topics that build the output, and drops removed ones`() = runTest {
        val (transport, source) = start(FakeMqttTransport())
        transport.deliver(lightTopic, lightConfig, retain = true)
        runCurrent()
        debounce()
        transport.deliver("z2m/lampe", """{"state":"ON"}""")
        transport.deliver("homeassistant/status", "online")
        runCurrent()
        assertEquals(setOf(lightTopic, "z2m/lampe"), source.journal().keys)

        val before = source.journalVersion.value
        transport.deliver(lightTopic, "", retain = true)
        runCurrent()
        assertTrue(source.journalVersion.value > before)
        assertEquals(emptySet(), source.journal().keys)
    }

    private val quiet = MqttDiscoverySource.SETTLE_QUIET.inWholeMilliseconds
    private val connected = LinkState.Connected(TransportMode.DIRECT)

    @Test
    fun `after connecting, restored configs the broker no longer holds are pruned with their override`() = runTest {
        val (transport, source) = start()
        source.restore(cached)
        runCurrent()
        transport.setLink(connected)
        runCurrent()
        assertTrue(lightId in source.entities.value)

        advanceTimeBy(quiet + 1)
        runCurrent()
        assertFalse(lightId in source.entities.value)
        assertEquals(emptySet(), source.journal().keys)
    }

    @Test
    fun `configs re-sent live survive and every state is settled`() = runTest {
        val (transport, source) = start()
        source.restore(cached)
        runCurrent()
        transport.setLink(connected)
        transport.deliver(lightTopic, lightConfig, retain = true) // the broker's retained replay
        runCurrent()
        // The replay lands in the first window, so settling takes one more quiet window.
        advanceTimeBy(2 * quiet + 1)
        runCurrent()
        val light = source.entities.value.getValue(lightId)
        assertEquals("Salon", light.area)
        // The override was only restored, never re-sent: it is gone, so the name falls back.
        assertEquals("Lampe", light.name)
        assertFalse(source.states().first().getValue(lightId).stale)
    }

    @Test
    fun `a steady stream still settles by the cap`() = runTest {
        val (transport, source) = start()
        source.restore(cached)
        runCurrent()
        transport.setLink(connected)
        runCurrent()
        repeat(12) {
            transport.deliver("homeassistant/noise/$it", "x") // subscribed, so it counts as live traffic
            advanceTimeBy(1_000)
            runCurrent()
        }
        assertFalse(lightId in source.entities.value)
    }

    @Test
    fun `a restore that arrives after settle is ignored`() = runTest {
        val (_, source) = start(FakeMqttTransport())
        advanceTimeBy(quiet + 1)
        runCurrent()
        source.restore(cached)
        runCurrent()
        assertFalse(lightId in source.entities.value)
        assertTrue(source.journal().isEmpty())
    }
}
