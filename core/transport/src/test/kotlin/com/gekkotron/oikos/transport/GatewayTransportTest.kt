package com.gekkotron.oikos.transport

import com.gekkotron.oikos.transport.testing.FakeGatewayApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayTransportTest {
    private val api = FakeGatewayApi()
    private val connected = LinkState.Connected(TransportMode.GATEWAY)

    private fun TestScope.started(topics: Set<String> = setOf("homeassistant/#")): Pair<GatewayTransport, MutableList<String>> {
        val t = GatewayTransport(api, backgroundScope, backoff = Backoff(jitter = 0.0))
        val got = mutableListOf<String>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { t.messages().collect { got += "${it.topic}=${it.payloadString()}" } }
        t.start()
        backgroundScope.launch { t.setSubscriptions(topics) }
        advanceTimeBy(301.milliseconds)
        runCurrent()
        return t to got
    }

    @Test
    fun `the first stream opens after the debounce and connected makes the link connected`() = runTest {
        val (t, got) = started()
        assertEquals(1, api.streams.size)
        assertEquals(setOf("homeassistant/#"), api.streams[0].topics)
        assertEquals(LinkState.Connecting, t.link.value)
        api.streams[0].connect()
        api.streams[0].message("homeassistant/light/x/config", "{}", retained = true)
        runCurrent()
        assertEquals(connected, t.link.value)
        assertEquals(listOf("homeassistant/light/x/config={}"), got)
    }

    @Test
    fun `a burst of subscription changes opens a single stream with the final set`() = runTest {
        val t = GatewayTransport(api, backgroundScope, backoff = Backoff(jitter = 0.0))
        t.start()
        t.setSubscriptions(setOf("a"))
        advanceTimeBy(100.milliseconds)
        t.setSubscriptions(setOf("a", "b"))
        advanceTimeBy(100.milliseconds)
        t.setSubscriptions(setOf("a", "b", "c"))
        advanceTimeBy(301.milliseconds)
        runCurrent()
        assertEquals(listOf(setOf("a", "b", "c")), api.streams.map { it.topics })
    }

    @Test
    fun `a new topic set reopens make-before-break without losing messages`() = runTest {
        val (t, got) = started(setOf("a"))
        api.streams[0].connect()
        runCurrent()
        t.setSubscriptions(setOf("a", "b"))
        advanceTimeBy(301.milliseconds)
        runCurrent()
        assertEquals(2, api.streams.size)
        api.streams[0].message("a", "1") // still current
        api.streams[1].message("b", "2") // buffered until the new stream is connected
        runCurrent()
        assertEquals(listOf("a=1"), got)
        assertTrue(api.streams[0].active)
        assertEquals(connected, t.link.value)
        api.streams[1].connect()
        runCurrent()
        assertEquals(listOf("a=1", "b=2"), got)
        assertFalse(api.streams[0].active, "the old stream is closed after the flip")
        assertEquals(connected, t.link.value)
    }

    @Test
    fun `a dropped stream shows Reconnecting and reopens after the backoff`() = runTest {
        val (t, _) = started()
        api.streams[0].connect()
        runCurrent()
        api.streams[0].end(IOException("reset"))
        runCurrent()
        assertEquals(LinkState.Reconnecting(1), t.link.value)
        advanceTimeBy(999.milliseconds)
        assertEquals(1, api.streams.size)
        advanceTimeBy(2.milliseconds)
        runCurrent()
        assertEquals(2, api.streams.size)
        assertEquals(LinkState.Reconnecting(1), t.link.value, "still reconnecting until the new stream connects")
        api.streams[1].connect()
        runCurrent()
        assertEquals(connected, t.link.value)
    }

    @Test
    fun `a stream that drops at once does not reset the attempt count`() = runTest {
        val (t, _) = started()
        api.streams[0].connect()
        runCurrent()
        api.streams[0].end()
        runCurrent()
        assertEquals(LinkState.Reconnecting(1), t.link.value)
        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        api.streams[1].connect()
        runCurrent()
        api.streams[1].end()
        runCurrent()
        assertEquals(LinkState.Reconnecting(2), t.link.value)
    }

    @Test
    fun `a stream that stayed up resets the attempt count`() = runTest {
        val (t, _) = started()
        api.streams[0].end()
        runCurrent()
        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        api.streams[1].connect()
        advanceTimeBy(31.seconds)
        api.streams[1].end()
        runCurrent()
        assertEquals(LinkState.Reconnecting(1), t.link.value)
    }

    @Test
    fun `a refusal goes offline with its reason and never retries`() = runTest {
        api.onOpen = { it.end(GatewayRefused(Refusal.KeyRejected)) }
        val (t, _) = started()
        runCurrent()
        assertEquals(LinkState.Offline(LinkState.Offline.KEY_REJECTED), t.link.value)
        t.setSubscriptions(setOf("other/#"))
        advanceTimeBy(5.minutes)
        assertEquals(1, api.streams.size)
    }

    @Test
    fun `a clock refusal says so`() = runTest {
        api.onOpen = { it.end(GatewayRefused(Refusal.ClockSkew)) }
        val (t, _) = started()
        runCurrent()
        assertEquals(LinkState.Offline(LinkState.Offline.CLOCK_SKEW), t.link.value)
    }

    @Test
    fun `refused MQTT credentials behind the gateway are REJECTED`() = runTest {
        api.onOpen = { it.end(GatewayRefused(Refusal.BrokerRejected)) }
        val (t, _) = started()
        runCurrent()
        assertEquals(LinkState.Offline(LinkState.Offline.REJECTED), t.link.value)
        advanceTimeBy(5.minutes)
        assertEquals(1, api.streams.size)
    }

    @Test
    fun `publish goes through the api only while connected`() = runTest {
        val (t, _) = started()
        assertFailsWith<PublishFailed> { t.publish("x/set", "ON".encodeToByteArray(), 1, false) }
        api.streams[0].connect()
        runCurrent()
        t.publish("x/set", "ON".encodeToByteArray(), 1, false)
        assertEquals(listOf("x/set"), api.published.map { it.topic })
        api.failPublish = "MQTT error: boom"
        assertEquals("MQTT error: boom", assertFailsWith<PublishFailed> { t.publish("x/set", byteArrayOf(), 1, false) }.message)
    }

    @Test
    fun `close ends the stream and goes offline`() = runTest {
        val (t, _) = started()
        api.streams[0].connect()
        runCurrent()
        t.close()
        runCurrent()
        assertFalse(api.streams[0].active)
        assertEquals(LinkState.Offline(LinkState.Offline.CLOSED), t.link.value)
    }
}
