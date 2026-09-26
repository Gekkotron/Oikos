package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.GatewayConfig
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.feature.onboarding.TestFailure
import com.gekkotron.oikos.feature.onboarding.TestState
import com.gekkotron.oikos.model.Bindings
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.transport.GatewayRefused
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.Refusal
import com.gekkotron.oikos.transport.testing.EmbeddedBroker
import com.gekkotron.oikos.transport.testing.FakeGatewayApi
import com.gekkotron.oikos.transport.testing.freePort
import com.hivemq.client.mqtt.datatypes.MqttQos
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DirectConnectionTesterTest {
    private val brokers = mutableListOf<EmbeddedBroker>()
    private val tester = DirectConnectionTester(clientId = { "oikos-test-setup" }, connectTimeout = 2.seconds)

    @After
    fun tearDown() = brokers.forEach { it.close() }

    private fun broker(credentials: Pair<String, String>? = null) = EmbeddedBroker(credentials).also { it.start(); brokers += it }

    private fun draft(port: Int, user: String? = null, pass: String? = null) =
        ConnectionProfile.Direct(LocalBroker("127.0.0.1", port, username = user, password = pass))

    @Test
    fun `reachable broker reports discovered devices`() = runBlocking {
        val b = broker()
        val peer = b.peer()
        val config = """{"name":"Kettle","unique_id":"kettle","command_topic":"k/set","state_topic":"k/state",
            "device":{"identifiers":["kettle"],"name":"Kitchen kettle"}}"""
        peer.publishWith().topic("homeassistant/switch/kettle/config").payload(config.encodeToByteArray())
            .qos(MqttQos.AT_LEAST_ONCE).retain(true).send()
        val connected = withTimeout(10.seconds) {
            tester.test(draft(b.port)).first { it is TestState.Connected && it.devices.isNotEmpty() }
        }
        assertEquals(TestState.Connected(listOf("Kitchen kettle")), connected)
        peer.disconnect()
    }

    @Test
    fun `unreachable broker fails with Unreachable after the timeout`() = runBlocking {
        val result = withTimeout(10.seconds) { tester.test(draft(freePort())).first { it is TestState.Failed } }
        assertEquals(TestState.Failed(TestFailure.Unreachable), result)
    }

    @Test
    fun `rejected credentials fail with Rejected`() = runBlocking {
        val b = broker(credentials = "oikos" to "secret")
        val result = withTimeout(10.seconds) { tester.test(draft(b.port, "oikos", "wrong")).first { it is TestState.Failed } }
        assertEquals(TestState.Failed(TestFailure.Rejected), result)
    }

    @Test
    fun `a burst of retained discovery configs collapses into far fewer Connected emissions, with the last one complete`() = runBlocking {
        val b = broker()
        val peer = b.peer()
        val count = 60
        repeat(count) { i ->
            val id = "device-$i"
            val name = "Device %02d".format(i)
            val config = """{"name":"Dev $i","unique_id":"$id","command_topic":"$id/set","state_topic":"$id/state",
                "device":{"identifiers":["$id"],"name":"$name"}}"""
            peer.publishWith().topic("homeassistant/switch/$id/config").payload(config.encodeToByteArray())
                .qos(MqttQos.AT_LEAST_ONCE).retain(true).send()
        }
        var connectedEmissions = 0
        val marks = mutableListOf<Long>()
        val last = withTimeout(15.seconds) {
            tester.test(draft(b.port))
                .onEach { if (it is TestState.Connected) { connectedEmissions++; marks += System.nanoTime() } }
                .first { it is TestState.Connected && it.devices.size == count }
        } as TestState.Connected
        val gapsMs = marks.zipWithNext { a, z -> (z - a) / 1_000_000 }
        val expected = (0 until count).map { "Device %02d".format(it) }.sortedBy { it.lowercase() }
        assertEquals(expected, last.devices)
        assertTrue(
            connectedEmissions < count / 2,
            "expected far fewer than $count Connected emissions for a burst of $count configs, got $connectedEmissions",
        )
        // The throttle guarantees at most one Connected update per ~250ms; allow scheduling jitter down to 200ms.
        if (gapsMs.isNotEmpty()) {
            assertTrue(
                gapsMs.min() >= 200,
                "expected Connected emissions at least ~250ms apart, got gaps (ms): $gapsMs",
            )
        }
        peer.disconnect()
    }

    @Test
    fun `devices without a device block are listed by entity name, sorted`() {
        val names = deviceNames(
            devices = mapOf("d1" to device("d1", "Zeta hub")),
            entities = mapOf("a" to entity("a", "d1", "Ignored"), "b" to entity("b", null, "alpha plug")),
        )
        assertEquals(listOf("alpha plug", "Zeta hub"), names)
    }

    private fun device(id: String, name: String) = com.gekkotron.oikos.model.Device(id, name, null, null, null)

    // Entity's constructor also requires `bindings` (9th param, no default); NoBindings mirrors
    // feature/home's TestEntities.kt helper.
    private object NoBindings : Bindings

    private fun entity(id: String, deviceId: String?, name: String) = Entity(
        uniqueId = id,
        deviceId = deviceId,
        name = name,
        kind = Kind.SWITCH,
        deviceClass = null,
        unit = null,
        icon = null,
        capabilities = Capabilities(),
        bindings = NoBindings,
        area = UNASSIGNED_AREA,
        ui = UiOverride(),
    )

    private val kettle = """{"name":"Kettle","unique_id":"kettle","command_topic":"k/set","state_topic":"k/state",
        "device":{"identifiers":["kettle"],"name":"Kitchen kettle"}}"""

    private fun awayDraft() = ConnectionProfile.Direct(
        LocalBroker("127.0.0.1", freePort()),
        gateway = GatewayConfig("https://gw.example", ByteArray(32), "192.168.1.91"),
    )

    private fun gatewayTester(api: FakeGatewayApi) =
        DirectConnectionTester(clientId = { "oikos-test-setup" }, connectTimeout = 1.seconds, gatewayTimeout = 3.seconds, gatewayApi = { api })

    @Test
    fun `an unreachable broker falls back to the gateway when the draft has one`() = runBlocking {
        val api = FakeGatewayApi().apply {
            onOpen = { it.connect(); it.message("homeassistant/switch/kettle/config", kettle, retained = true) }
        }
        val connected = withTimeout(10.seconds) {
            gatewayTester(api).test(awayDraft()).first { it is TestState.Connected && it.devices.isNotEmpty() }
        }
        assertEquals(TestState.Connected(listOf("Kitchen kettle"), viaGateway = true), connected)
    }

    @Test
    fun `a gateway that rejects the key fails with KeyRejected`() = runBlocking {
        val api = FakeGatewayApi().apply { onOpen = { it.end(GatewayRefused(Refusal.KeyRejected)) } }
        val failed = withTimeout(10.seconds) { gatewayTester(api).test(awayDraft()).first { it is TestState.Failed } }
        assertEquals(TestState.Failed(TestFailure.KeyRejected), failed)
    }

    @Test
    fun `a gateway that never connects fails as unreachable`() = runBlocking {
        val failed = withTimeout(10.seconds) { gatewayTester(FakeGatewayApi()).test(awayDraft()).first { it is TestState.Failed } }
        assertEquals(TestState.Failed(TestFailure.Unreachable), failed)
    }

    @Test
    fun `gateway links map to test failures`() {
        assertEquals(TestFailure.KeyRejected, gatewayFailure(LinkState.Offline(LinkState.Offline.KEY_REJECTED)))
        assertEquals(TestFailure.ClockSkew, gatewayFailure(LinkState.Offline(LinkState.Offline.CLOCK_SKEW)))
        assertEquals(TestFailure.Rejected, gatewayFailure(LinkState.Offline(LinkState.Offline.REJECTED)))
        assertEquals(TestFailure.Other("gateway refused"), gatewayFailure(LinkState.Offline(LinkState.Offline.GATEWAY_REFUSED)))
        assertEquals(TestFailure.Unreachable, gatewayFailure(null))
        assertEquals(TestFailure.Unreachable, gatewayFailure(LinkState.Reconnecting(2)))
    }
}
