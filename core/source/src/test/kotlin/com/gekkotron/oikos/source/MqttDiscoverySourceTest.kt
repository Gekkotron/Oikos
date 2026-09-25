package com.gekkotron.oikos.source

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandFailure
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.Diagnostic
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.MqttMessage
import com.gekkotron.oikos.transport.testing.FakeMqttTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MqttDiscoverySourceTest {
    private val lightTopic = "homeassistant/light/0x01/light/config"
    private val lightId = "0x01_light_zigbee2mqtt"
    private val lightConfig =
        """{"schema":"json","command_topic":"z2m/lampe/set","state_topic":"z2m/lampe","brightness":true,"unique_id":"$lightId","name":null,"device":{"identifiers":["z_lampe"],"name":"Lampe","suggested_area":"Salon"}}"""

    private fun switchConfig(id: String, extra: String = "") =
        """{"command_topic":"sw/$id/set","state_topic":"sw/$id","unique_id":"$id"$extra}"""

    private class Harness(
        val transport: FakeMqttTransport,
        val source: MqttDiscoverySource,
        val diagnostics: MutableList<Diagnostic>,
    )

    private fun TestScope.start(transport: FakeMqttTransport = FakeMqttTransport()): Harness {
        val diagnostics = mutableListOf<Diagnostic>()
        val source = MqttDiscoverySource(transport, backgroundScope, diagnostics = { diagnostics += it })
        runCurrent()
        return Harness(transport, source, diagnostics)
    }

    private fun TestScope.settle() {
        advanceTimeBy(MqttDiscoverySource.SUBSCRIPTION_DEBOUNCE.inWholeMilliseconds + 1)
        runCurrent()
    }

    private suspend fun Harness.state(id: String) = source.states().first().getValue(id)

    @Test
    fun `link mirrors the transport`() = runTest {
        val h = start()
        h.transport.setLink(LinkState.Offline("test"))
        assertEquals(LinkState.Offline("test"), h.source.link.value)
    }

    @Test
    fun `subscribes to discovery and overrides first`() = runTest {
        val h = start()
        assertEquals(setOf("homeassistant/#", "oikos/ui/#"), h.transport.subscriptionHistory.first())
    }

    @Test
    fun `retained config becomes an entity and its state topic is subscribed after the debounce`() = runTest {
        val transport = FakeMqttTransport()
        transport.deliver(lightTopic, lightConfig, retain = true)
        val h = start(transport)

        val light = h.source.entities.value.getValue(lightId)
        assertEquals("Lampe", light.name)
        assertEquals("Salon", light.area)
        assertEquals(setOf("homeassistant/#", "oikos/ui/#"), h.transport.subscriptions)

        settle()
        assertTrue("z2m/lampe" in h.transport.subscriptions)
        h.transport.deliver("z2m/lampe", """{"state":"ON","brightness":254}""")
        runCurrent()
        assertEquals(StateValue.Light(true, 254), h.state(lightId).value)
    }

    @Test
    fun `subscription updates are debounced`() = runTest {
        val h = start()
        h.transport.deliver("homeassistant/switch/a/config", switchConfig("a"))
        h.transport.deliver("homeassistant/switch/b/config", switchConfig("b"))
        h.transport.deliver("homeassistant/switch/c/config", switchConfig("c"))
        runCurrent()
        settle()
        assertEquals(2, h.transport.subscriptionHistory.size)
        assertTrue(h.transport.subscriptions.containsAll(setOf("sw/a", "sw/b", "sw/c")))
    }

    @Test
    fun `override name and area take precedence and can be cleared`() = runTest {
        val h = start()
        h.transport.deliver(lightTopic, lightConfig)
        h.transport.deliver("oikos/ui/$lightId", """{"name":"Lampe salon","area":"Cuisine","favorite":true}""")
        runCurrent()
        var light = h.source.entities.value.getValue(lightId)
        assertEquals("Lampe salon", light.name)
        assertEquals("Cuisine", light.area)
        assertTrue(light.ui.favorite)

        h.transport.deliver("oikos/ui/$lightId", "")
        runCurrent()
        light = h.source.entities.value.getValue(lightId)
        assertEquals("Lampe", light.name)
        assertEquals("Salon", light.area)
    }

    @Test
    fun `areas resolve by id or name and are ordered declared, implicit, unassigned`() = runTest {
        val h = start()
        h.transport.deliver("oikos/ui/_areas", """{"areas":[{"id":"garage","name":"Garage","order":2},{"id":"kitchen","name":"Cuisine","order":1}]}""")
        h.transport.deliver(lightTopic, lightConfig) // suggested_area Salon -> implicit
        h.transport.deliver("homeassistant/switch/a/config", switchConfig("a"))
        h.transport.deliver("homeassistant/switch/b/config", switchConfig("b"))
        h.transport.deliver("oikos/ui/a", """{"area":"Cuisine"}""")
        runCurrent()

        assertEquals("kitchen", h.source.entities.value.getValue("a").area)
        assertEquals(UNASSIGNED_AREA, h.source.entities.value.getValue("b").area)
        assertEquals(listOf("kitchen", "garage", "Salon", UNASSIGNED_AREA), h.source.areas.value.map { it.id })
    }

    @Test
    fun `empty config removes the entity and its state`() = runTest {
        val h = start()
        h.transport.deliver(lightTopic, lightConfig)
        runCurrent()
        h.transport.deliver(lightTopic, "")
        runCurrent()
        assertFalse(lightId in h.source.entities.value)
        assertFalse(lightId in h.source.states().first())
    }

    // Review Focus 4
    @Test
    fun `config replaced under the same topic drops the old entity`() = runTest {
        val h = start()
        h.transport.deliver("homeassistant/switch/plug/config", switchConfig("old"))
        runCurrent()
        h.transport.deliver("homeassistant/switch/plug/config", switchConfig("new"))
        runCurrent()
        assertEquals(setOf("new"), h.source.entities.value.keys)
    }

    // Review Focus 3
    @Test
    fun `malformed config does not disturb other entities`() = runTest {
        val h = start()
        h.transport.deliver(lightTopic, lightConfig)
        h.transport.deliver("homeassistant/switch/bad/config", "{oops")
        runCurrent()
        assertEquals(setOf(lightId), h.source.entities.value.keys)
        assertEquals(1, h.diagnostics.size)
        assertTrue("homeassistant/switch/bad/config" in h.diagnostics.single().message)

        h.transport.deliver("homeassistant/switch/bad/config", switchConfig("fixed"))
        runCurrent()
        assertEquals(setOf(lightId, "fixed"), h.source.entities.value.keys)
    }

    @Test
    fun `send publishes, marks pending, and a state message confirms`() = runTest {
        val h = start()
        h.transport.deliver(lightTopic, lightConfig)
        runCurrent()
        settle()
        h.transport.deliver("z2m/lampe", """{"state":"OFF"}""")
        runCurrent()

        assertEquals(CommandResult.Accepted, h.source.send(lightId, Command.TurnOn()))
        assertEquals("z2m/lampe/set" to """{"state":"ON"}""", h.transport.publishedStrings().last())
        assertTrue(h.state(lightId).pending)

        h.transport.deliver("z2m/lampe", """{"state":"ON"}""")
        runCurrent()
        assertFalse(h.state(lightId).pending)
        assertEquals(StateValue.Light(true, null), h.state(lightId).value)
    }

    @Test
    fun `unconfirmed command reverts and is reported`() = runTest {
        val h = start()
        val failures = mutableListOf<CommandFailure>()
        backgroundScope.launch { h.source.commandFailures.collect { failures += it } }
        h.transport.deliver("homeassistant/switch/a/config", switchConfig("a"))
        runCurrent()
        settle()
        h.transport.deliver("sw/a", "OFF")
        runCurrent()

        h.source.send("a", Command.TurnOn())
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(1, failures.size)
        assertEquals(StateValue.OnOff(false), h.state("a").value)
        assertFalse(h.state("a").pending)
    }

    @Test
    fun `optimistic entity without state topic is confirmed immediately`() = runTest {
        val h = start()
        h.transport.deliver("homeassistant/switch/o/config", """{"command_topic":"o/set","unique_id":"o"}""")
        runCurrent()
        h.source.send("o", Command.TurnOn())
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(StateValue.OnOff(true), h.state("o").value)
        assertFalse(h.state("o").pending)
    }

    @Test
    fun `send rejects unknown, unavailable and unsupported`() = runTest {
        val h = start()
        h.transport.deliver("homeassistant/switch/u/config", switchConfig("u", ""","availability_topic":"sw/u/avail""""))
        h.transport.deliver(lightTopic, lightConfig)
        runCurrent()
        assertIs<CommandResult.Rejected>(h.source.send("nope", Command.TurnOn()))
        assertEquals(CommandResult.Rejected("unavailable"), h.source.send("u", Command.TurnOn()))
        assertIs<CommandResult.Rejected>(h.source.send(lightId, Command.SetPosition(10)))
        assertTrue(h.transport.published.isEmpty())
    }

    @Test
    fun `publish failure is rejected and leaves no pending state`() = runTest {
        val h = start()
        h.transport.deliver(lightTopic, lightConfig)
        runCurrent()
        h.transport.failPublishes = true
        assertIs<CommandResult.Rejected>(h.source.send(lightId, Command.TurnOn()))
        assertFalse(h.state(lightId).pending)
        assertEquals(StateValue.Unknown, h.state(lightId).value)
    }

    @Test
    fun `setOverride applies locally and publishes retained JSON`() = runTest {
        val h = start()
        h.transport.deliver(lightTopic, lightConfig)
        runCurrent()
        h.source.setOverride(lightId, UiOverride(name = "X"))
        assertEquals("X", h.source.entities.value.getValue(lightId).name)
        assertEquals(MqttMessage("oikos/ui/$lightId", """{"name":"X"}""".encodeToByteArray(), retained = true), h.transport.published.last())
        runCurrent()
        assertEquals("X", h.source.entities.value.getValue(lightId).name)
    }

    @Test
    fun `setOverride rejects ids that cannot be topics`() = runTest {
        val h = start()
        assertFailsWith<IllegalArgumentException> { h.source.setOverride("a#b", UiOverride()) }
        assertFailsWith<IllegalArgumentException> { h.source.setOverride("a+b", UiOverride()) }
        assertFailsWith<IllegalArgumentException> { h.source.setOverride("_areas", UiOverride()) }
        assertFailsWith<IllegalArgumentException> { h.source.setOverride("", UiOverride()) }
    }

    @Test
    fun `setAreas publishes the areas document`() = runTest {
        val h = start()
        h.source.setAreas(listOf(Area("salon", "Salon", order = 1)))
        assertEquals("oikos/ui/_areas" to """{"areas":[{"id":"salon","name":"Salon","order":1}]}""", h.transport.publishedStrings().last())
        assertEquals(listOf("salon"), h.source.areas.value.map { it.id })
    }

    @Test
    fun `status and unknown topics are harmless`() = runTest {
        val h = start()
        h.transport.deliver("homeassistant/status", "online")
        h.transport.deliver("oikos/ui/x", "not json")
        runCurrent()
        assertTrue(h.source.entities.value.isEmpty())
        assertEquals(1, h.diagnostics.size)
    }

    private fun z2mSwitch(id: String, deviceName: String, extraDevice: String = "") =
        """{"command_topic":"z/$id/set","state_topic":"z/$id","unique_id":"$id","name":null,"device":{"identifiers":["$id"],"name":"$deviceName"$extraDevice}}"""

    @Test
    fun `a hierarchical device name gives the room when there is no suggested_area, and suggested_area wins`() = runTest {
        val h = start()
        h.transport.deliver("homeassistant/switch/p/config", z2mSwitch("p", "rooms/julien/prise"))
        h.transport.deliver("homeassistant/switch/q/config", z2mSwitch("q", "rooms/julien/radio", ""","suggested_area":"Bureau""""))
        runCurrent()
        val p = h.source.entities.value.getValue("p")
        assertEquals("prise", p.name)
        assertEquals("julien", p.area)
        assertEquals("Bureau", h.source.entities.value.getValue("q").area)
        assertEquals(Area("julien", "Julien"), h.source.areas.value.first { it.id == "julien" })
        assertEquals("prise", h.source.devices.value.getValue("p").name)
    }

    @Test
    fun `a declared area matches a path room by name ignoring case`() = runTest {
        val h = start()
        h.source.setAreas(listOf(Area("bedroom_j", "Julien")))
        h.transport.deliver("homeassistant/switch/p/config", z2mSwitch("p", "rooms/julien/prise"))
        runCurrent()
        assertEquals("bedroom_j", h.source.entities.value.getValue("p").area)
        assertEquals(listOf("bedroom_j"), h.source.areas.value.map { it.id })
    }

    @Test
    fun `an override still beats the path room`() = runTest {
        val h = start()
        h.transport.deliver("homeassistant/switch/p/config", z2mSwitch("p", "rooms/julien/prise"))
        runCurrent()
        h.source.setOverride("p", UiOverride(area = "kitchen"))
        assertEquals("kitchen", h.source.entities.value.getValue("p").area)
    }
}
