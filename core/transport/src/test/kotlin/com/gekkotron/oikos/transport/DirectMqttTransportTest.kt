package com.gekkotron.oikos.transport

import com.gekkotron.oikos.transport.testing.EmbeddedBroker
import com.gekkotron.oikos.transport.testing.freePort
import com.hivemq.client.mqtt.datatypes.MqttQos
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DirectMqttTransportTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val brokers = mutableListOf<EmbeddedBroker>()
    private val transports = mutableListOf<DirectMqttTransport>()

    @AfterEach
    fun tearDown() = runBlocking {
        transports.forEach { it.close() }
        brokers.forEach { it.close() }
        scope.cancel()
    }

    private fun broker(credentials: Pair<String, String>? = null, websocket: Boolean = false) =
        EmbeddedBroker(credentials, websocket).also { it.start(); brokers += it }

    private fun transport(port: Int, user: String? = null, pass: String? = null, ws: Boolean = false) = DirectMqttTransport(
        TransportSpec.Direct("127.0.0.1", port, tls = false, ws = ws, username = user, password = pass, clientId = "oikos-test-${transports.size}"),
        scope,
        reconnectInitial = 200.milliseconds,
        reconnectMax = 1.seconds,
    ).also { transports += it }

    private suspend fun MqttTransport.awaitConnected() = withTimeout(10.seconds) { link.first { it is LinkState.Connected } }

    /** Records messages; subscribed before anything can arrive. */
    private fun MqttTransport.record(): MutableList<MqttMessage> {
        val list = Collections.synchronizedList(mutableListOf<MqttMessage>())
        scope.launch(start = CoroutineStart.UNDISPATCHED) { messages().collect { list += it } }
        return list
    }

    private suspend fun eventually(check: () -> Boolean) = withTimeout(10.seconds) { while (!check()) delay(20) }

    @Test
    fun `connects and reports Connected(DIRECT)`() = runBlocking {
        val t = transport(broker().port)
        t.start()
        assertEquals(LinkState.Connected(TransportMode.DIRECT), t.awaitConnected())
    }

    @Test
    fun `good credentials connect`() = runBlocking {
        val b = broker(credentials = "oikos" to "p@ss wörd:1")
        val t = transport(b.port, "oikos", "p@ss wörd:1")
        t.start()
        t.awaitConnected()
        Unit
    }

    @Test
    fun `bad credentials go Offline without retrying`() = runBlocking {
        val b = broker(credentials = "oikos" to "secret")
        val t = transport(b.port, "oikos", "wrong")
        t.start()
        val offline = withTimeout(10.seconds) {
            t.link.first { it is LinkState.Offline && it.reason == LinkState.Offline.REJECTED }
        }
        assertEquals(DirectFailure.Rejected, t.failure.value)
        delay(1500) // longer than reconnectMax: a retry would have changed the link
        assertEquals(offline, t.link.value)
    }

    @Test
    fun `unreachable broker reports Unreachable and keeps retrying`() = runBlocking {
        val t = transport(freePort())
        t.start()
        eventually { t.failure.value == DirectFailure.Unreachable }
        withTimeout(10.seconds) { t.link.first { it is LinkState.Reconnecting } }
        Unit
    }

    @Test
    fun `receives retained messages on subscribe`() = runBlocking {
        val b = broker()
        val peer = b.peer()
        peer.publishWith().topic("homeassistant/light/a/config").payload("{}".encodeToByteArray()).qos(MqttQos.AT_LEAST_ONCE).retain(true).send()
        val t = transport(b.port)
        val got = t.record()
        t.start()
        t.awaitConnected()
        t.setSubscriptions(setOf("homeassistant/#"))
        eventually { got.any { it.topic == "homeassistant/light/a/config" } }
        // Not asserting `retained`: Moquette 0.17's redelivery-on-subscribe path for QoS >= 1
        // (Session#sendPublishQos1's "skip queue" fast path -- the common case here: empty queue,
        // free inflight slots, writable channel) always calls
        // MQTTConnection#createNotRetainedPublishMessage, unconditionally clearing the wire RETAIN
        // bit; and Moquette treats any QoS 0 retained publish as a "clear retained" command
        // (PostOffice#receivedPublishQos0), so it's never actually stored either way. No QoS lets
        // this broker redeliver a retained message with the bit correctly set. DirectMqttTransport's
        // own handling is a direct, unconditional pass-through of HiveMQ's `Mqtt3Publish.isRetain()`
        // (see DirectMqttTransport.onConnected), so it is not in question here; see task-2-report.md.
        peer.disconnect()
    }

    @Test
    fun `subscription diff unsubscribes removed filters`() = runBlocking {
        val b = broker()
        val t = transport(b.port)
        val got = t.record()
        t.start()
        t.awaitConnected()
        t.setSubscriptions(setOf("a/#", "b/#"))
        t.setSubscriptions(setOf("b/#"))
        val peer = b.peer()
        peer.publishWith().topic("a/1").payload("x".encodeToByteArray()).qos(MqttQos.AT_LEAST_ONCE).send()
        peer.publishWith().topic("b/1").payload("y".encodeToByteArray()).qos(MqttQos.AT_LEAST_ONCE).send()
        eventually { got.any { it.topic == "b/1" } }
        delay(300)
        assertTrue(got.none { it.topic == "a/1" })
        peer.disconnect()
    }

    @Test
    fun `resubscribes after the broker restarts`() = runBlocking {
        val b = broker()
        val t = transport(b.port)
        val got = t.record()
        t.start()
        t.awaitConnected()
        t.setSubscriptions(setOf("x/#"))
        b.stop()
        withTimeout(10.seconds) { t.link.first { it is LinkState.Reconnecting } }
        b.start()
        t.awaitConnected()
        val peer = b.peer()
        // Retained, so it arrives even if our resubscribe lands after the publish.
        peer.publishWith().topic("x/1").payload("1".encodeToByteArray()).qos(MqttQos.AT_LEAST_ONCE).retain(true).send()
        eventually { got.any { it.topic == "x/1" } }
        peer.disconnect()
    }

    @Test
    fun `QoS 1 publish reaches another client`() = runBlocking {
        val b = broker()
        val peer = b.peer()
        val seen = CompletableDeferred<String>()
        peer.toAsync().subscribeWith().topicFilter("cmd/#").callback { seen.complete(it.payloadAsBytes.decodeToString()) }.send().get()
        val t = transport(b.port)
        t.start()
        t.awaitConnected()
        t.publish("cmd/lamp", "ON".encodeToByteArray(), qos = 1)
        assertEquals("ON", withTimeout(5.seconds) { seen.await() })
        peer.disconnect()
    }

    @Test
    fun `publish while disconnected throws PublishFailed`() = runBlocking {
        val t = transport(broker().port) // never started
        assertFailsWith<PublishFailed> { t.publish("a/b", byteArrayOf(1)) }
        Unit
    }

    @Test
    fun `a burst of 1000 messages arrives complete and in order`() = runBlocking {
        val b = broker()
        val t = transport(b.port)
        val got = t.record()
        t.start()
        t.awaitConnected()
        t.setSubscriptions(setOf("burst/#"))
        val peer = b.peer()
        repeat(1000) { peer.publishWith().topic("burst/n").payload("$it".encodeToByteArray()).qos(MqttQos.AT_LEAST_ONCE).send() }
        eventually { got.size >= 1000 }
        assertEquals((0 until 1000).map { "$it" }, got.map { it.payloadString() })
        peer.disconnect()
    }

    @Test
    fun `connects over WebSocket on path mqtt`() = runBlocking {
        val b = broker(websocket = true)
        val t = transport(b.wsPort, ws = true)
        t.start()
        t.awaitConnected()
        Unit
    }

    @Test
    fun `close goes Offline and stops reconnecting`() = runBlocking {
        val b = broker()
        val t = transport(b.port)
        t.start()
        t.awaitConnected()
        t.close()
        assertEquals(LinkState.Offline(LinkState.Offline.CLOSED), t.link.value)
        b.stop()
        b.start()
        delay(1500)
        assertEquals(LinkState.Offline(LinkState.Offline.CLOSED), t.link.value)
    }
}
