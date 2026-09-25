package com.gekkotron.oikos.source

import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.model.UiOverrides
import com.gekkotron.oikos.transport.LinkState
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
}
