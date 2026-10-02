package com.gekkotron.oikos.transport

import com.gekkotron.oikos.transport.testing.FakeGatewayApi
import com.gekkotron.oikos.transport.testing.FakeManagedTransport
import kotlinx.coroutines.CancellationException
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
    fun `an incoming stream that drops is retried until the new topic set is live`() = runTest {
        val (t, _) = started(setOf("a"))
        api.streams[0].connect()
        runCurrent()
        t.setSubscriptions(setOf("a", "b"))
        advanceTimeBy(301.milliseconds)
        runCurrent()
        assertEquals(2, api.streams.size)
        api.streams[1].end(IOException("502")) // before it ever connected
        runCurrent()
        assertEquals(connected, t.link.value, "the live stream is untouched")
        assertTrue(api.streams[0].active)
        advanceTimeBy(999.milliseconds)
        runCurrent()
        assertEquals(2, api.streams.size, "the retry waits for the backoff")
        advanceTimeBy(2.milliseconds)
        runCurrent()
        assertEquals(3, api.streams.size)
        assertEquals(setOf("a", "b"), api.streams[2].topics)
        assertEquals(connected, t.link.value)
        api.streams[2].connect()
        runCurrent()
        assertFalse(api.streams[0].active, "the old stream is closed after the flip")
        assertTrue(api.streams[2].active)
        assertEquals(connected, t.link.value)
        advanceTimeBy(5.minutes)
        assertEquals(3, api.streams.size, "nothing more to open once the set is live")
    }

    @Test
    fun `a newer topic set supersedes a pending retry`() = runTest {
        val (t, _) = started(setOf("a"))
        api.streams[0].connect()
        runCurrent()
        t.setSubscriptions(setOf("a", "b"))
        advanceTimeBy(301.milliseconds)
        runCurrent()
        api.streams[1].end(IOException("502"))
        runCurrent()
        t.setSubscriptions(setOf("a", "c")) // during the 1 s backoff
        advanceTimeBy(301.milliseconds)
        runCurrent()
        assertEquals(listOf(setOf("a"), setOf("a", "b"), setOf("a", "c")), api.streams.map { it.topics })
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(3, api.streams.size, "the superseded {a,b} retry never runs")
    }

    @Test
    fun `a refusal on the incoming stream is applied`() = runTest {
        val (t, _) = started(setOf("a"))
        api.streams[0].connect()
        runCurrent()
        api.onOpen = { it.end(GatewayRefused(Refusal.ClockSkew)) }
        t.setSubscriptions(setOf("a", "b"))
        advanceTimeBy(301.milliseconds)
        runCurrent()
        assertEquals(LinkState.Offline(LinkState.Offline.CLOCK_SKEW), t.link.value)
        assertFalse(api.streams[0].active, "a refusal closes the old stream too: Offline, no retry")
        assertFailsWith<PublishFailed> { t.publish("x/set", byteArrayOf(), 1, false) }
        t.setSubscriptions(setOf("a", "b", "c"))
        advanceTimeBy(5.minutes)
        assertEquals(2, api.streams.size)
        assertEquals(LinkState.Offline(LinkState.Offline.CLOCK_SKEW), t.link.value)
    }

    @Test
    fun `a stream ended by a stray CancellationException is treated as dropped`() = runTest {
        val (t, _) = started()
        api.streams[0].connect()
        runCurrent()
        api.streams[0].end(CancellationException("sdk"))
        runCurrent()
        assertEquals(LinkState.Reconnecting(1), t.link.value)
        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        assertEquals(2, api.streams.size)
        api.streams[1].connect()
        runCurrent()
        assertEquals(connected, t.link.value)
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
    fun `a gateway candidate whose first stream drops is not given up by the outer handover`() = runTest {
        val outer = Handover(backgroundScope)
        val direct = FakeManagedTransport(TransportSpec.Direct("h", 1883, tls = false, ws = false, username = null, password = null, clientId = "c"))
        direct.fake.setLink(LinkState.Connected(TransportMode.DIRECT))
        outer.switchTo(direct)
        val t = GatewayTransport(api, backgroundScope, backoff = Backoff(jitter = 0.0))
        var switched: Boolean? = null
        backgroundScope.launch { switched = outer.switchTo(t) }
        backgroundScope.launch { t.setSubscriptions(setOf("homeassistant/#")) }
        advanceTimeBy(301.milliseconds)
        runCurrent()
        api.streams[0].end(IOException("502")) // before it ever connected: reconnect retries after 1 s
        runCurrent()
        assertEquals(null, switched, "a gateway that is reconnecting is still a candidate")
        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        api.streams[1].connect()
        runCurrent()
        assertEquals(true, switched)
    }

    @Test
    fun `a new topic set racing the reconnect opens a single stream`() = runTest {
        val (t, _) = started(setOf("a"))
        api.streams[0].connect()
        runCurrent()
        api.streams[0].end(IOException("reset")) // reconnect reopens after the 1 s backoff
        runCurrent()
        advanceTimeBy(700.milliseconds)
        t.setSubscriptions(setOf("a", "b")) // its debounce ends at the same instant as the backoff
        advanceTimeBy(301.milliseconds)
        runCurrent()
        assertEquals(listOf(setOf("a"), setOf("a", "b")), api.streams.map { it.topics })
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
