package com.gekkotron.oikos.transport

import com.gekkotron.oikos.transport.testing.FakeManagedTransport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.times

@OptIn(ExperimentalCoroutinesApi::class)
class TransportSelectorTest {
    private val directSpec = TransportSpec.Direct("192.168.1.91", 1883, tls = false, ws = false, username = null, password = null, clientId = "c")
    private val gatewaySpec = TransportSpec.Gateway("https://gw.example", ByteArray(32), null, null, null)
    private val directs = mutableListOf<FakeManagedTransport>()
    private val gateways = mutableListOf<FakeManagedTransport>()
    private var reachable = true
    private var probes = 0
    private val network = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** Gateways connect at once unless [gatewayConnects] is false. */
    private var gatewayConnects = true

    /** Direct candidates are born already rejected when this is true, before ever becoming current. */
    private var directRejectsOnCreate = false

    /** Every gateway factory call throws this (a malformed gateway URL in a saved profile). */
    private var gatewayFactoryThrows: Throwable? = null

    /** Every gateway's start() throws (it is created fine). */
    private var gatewayStartFails = false

    /** The next call to [probe] throws this instead of returning [reachable], then falls back to normal. */
    private var probeThrows: Throwable? = null

    private fun TestScope.selector(route: Route = Route.AUTO): Pair<TransportSelector, MutableList<String>> {
        val s = TransportSelector(
            TransportSpec.Routed(directSpec, gatewaySpec, route),
            direct = {
                FakeManagedTransport(it).also { d ->
                    if (directRejectsOnCreate) d.fake.setLink(LinkState.Offline(LinkState.Offline.REJECTED))
                    directs += d
                }
            },
            gateway = {
                gatewayFactoryThrows?.let { e -> throw e }
                FakeManagedTransport(it, failStart = gatewayStartFails).also { g ->
                    g.fake.setLink(if (gatewayConnects) LinkState.Connected(TransportMode.GATEWAY) else LinkState.Connecting)
                    gateways += g
                }
            },
            probe = {
                probes++
                probeThrows?.let {
                    probeThrows = null
                    throw it
                } ?: reachable
            },
            networkChanges = network,
            scope = backgroundScope,
        )
        val got = mutableListOf<String>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { s.messages().collect { got += it.payloadString() } }
        s.start()
        runCurrent()
        return s to got
    }

    @Test
    fun `route DIRECT runs only the direct transport and never probes`() = runTest {
        val (s, _) = selector(Route.DIRECT)
        advanceTimeBy(5.seconds * 60)
        assertEquals(1, directs.size)
        assertTrue(gateways.isEmpty())
        assertEquals(0, probes)
        assertEquals(LinkState.Connected(TransportMode.DIRECT), s.link.value)
    }

    @Test
    fun `route GATEWAY runs only the gateway`() = runTest {
        val (s, _) = selector(Route.GATEWAY)
        assertTrue(directs.isEmpty())
        assertEquals(1, gateways.size)
        assertEquals(LinkState.Connected(TransportMode.GATEWAY), s.link.value)
    }

    @Test
    fun `route GATEWAY with a factory that throws does not crash and goes Offline FAILED`() = runTest {
        gatewayFactoryThrows = IllegalArgumentException("Invalid URL host: \"my host\"")
        val (s, _) = selector(Route.GATEWAY)
        advanceTimeBy(5.seconds * 60)
        assertTrue(gateways.isEmpty())
        assertEquals(LinkState.Offline(LinkState.Offline.FAILED), s.link.value)
        s.close()
        runCurrent()
        assertEquals(LinkState.Offline(LinkState.Offline.CLOSED), s.link.value)
    }

    @Test
    fun `route GATEWAY whose start throws goes Offline FAILED, not Connecting forever`() = runTest {
        gatewayStartFails = true
        val (s, _) = selector(Route.GATEWAY)
        advanceTimeBy(5.seconds * 60)
        assertEquals(1, gateways.size)
        assertEquals(LinkState.Offline(LinkState.Offline.FAILED), s.link.value)
    }

    @Test
    fun `auto with no current and a factory that throws goes Offline FAILED, then recovers on the next trigger`() = runTest {
        reachable = false
        gatewayFactoryThrows = IllegalArgumentException("bad url")
        val (s, _) = selector()
        assertEquals(LinkState.Offline(LinkState.Offline.FAILED), s.link.value)
        gatewayFactoryThrows = null
        network.emit(Unit)
        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        assertEquals(1, gateways.size)
        assertEquals(LinkState.Connected(TransportMode.GATEWAY), s.link.value)
    }

    @Test
    fun `auto uses direct when the probe succeeds and the gateway otherwise`() = runTest {
        selector()
        assertEquals(1, directs.size)
        assertTrue(gateways.isEmpty())
    }

    @Test
    fun `auto starts on the gateway away from home`() = runTest {
        reachable = false
        val (s, _) = selector()
        assertTrue(directs.isEmpty())
        assertEquals(LinkState.Connected(TransportMode.GATEWAY), s.link.value)
    }

    @Test
    fun `a network change re-probes and switches make-before-break`() = runTest {
        val (s, got) = selector()
        s.setSubscriptions(setOf("#"))
        reachable = false
        network.emit(Unit)
        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        assertEquals(1, gateways.size)
        assertTrue(directs[0].closed)
        assertEquals(setOf("#"), gateways[0].fake.subscriptions)
        gateways[0].fake.deliver("t", "via-gateway")
        runCurrent()
        assertEquals(listOf("via-gateway"), got)
        assertEquals(LinkState.Connected(TransportMode.GATEWAY), s.link.value)
    }

    @Test
    fun `leaving Wi-Fi without a network callback still moves to the gateway`() = runTest {
        val (s, _) = selector()
        reachable = false
        gatewayConnects = false // the gateway is still connecting when the switch happens
        directs[0].fake.setLink(LinkState.Reconnecting(1))
        runCurrent()
        advanceTimeBy(2.seconds + 1.milliseconds)
        runCurrent()
        assertEquals(1, gateways.size)
        assertTrue(directs[0].closed, "a direct transport that is not connected is replaced at once")
        assertEquals(LinkState.Connecting, s.link.value)
    }

    @Test
    fun `on the gateway the selector re-probes every 60 s and returns home`() = runTest {
        reachable = false
        selector()
        reachable = true
        advanceTimeBy(60.seconds + 1.milliseconds)
        runCurrent()
        assertEquals(1, directs.size)
        assertTrue(gateways[0].closed)
    }

    @Test
    fun `rejected direct credentials fall back to the gateway until the next network change`() = runTest {
        selector()
        directs[0].fake.setLink(LinkState.Offline(LinkState.Offline.REJECTED))
        runCurrent()
        assertEquals(1, gateways.size)
        advanceTimeBy(3 * 60.seconds)
        runCurrent()
        assertEquals(1, directs.size, "no retry of the rejected direct route on re-probe")
        network.emit(Unit)
        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        assertEquals(2, directs.size)
    }

    @Test
    fun `a direct candidate rejected before it is current is not retried until a network change`() = runTest {
        reachable = false
        directRejectsOnCreate = true
        selector()
        assertTrue(directs.isEmpty())
        assertEquals(1, gateways.size)
        reachable = true
        // 60 s re-probe, then up to 15 s for the switch attempt itself to time out and be disposed.
        advanceTimeBy(75.seconds + 10.milliseconds)
        runCurrent()
        assertEquals(1, directs.size, "the rejected candidate was tried exactly once")
        advanceTimeBy(3 * 60.seconds)
        runCurrent()
        assertEquals(1, directs.size, "no retry of the rejected direct candidate on re-probe")
        network.emit(Unit)
        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        assertEquals(2, directs.size, "a network change tries direct again")
    }

    @Test
    fun `a probe that throws does not kill the selector loop`() = runTest {
        probeThrows = IllegalStateException("boom")
        val (s, _) = selector()
        assertTrue(directs.isEmpty())
        assertTrue(gateways.isEmpty())
        network.emit(Unit)
        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        assertEquals(1, directs.size)
        assertEquals(LinkState.Connected(TransportMode.DIRECT), s.link.value)
    }

    @Test
    fun `close during a pending switch closes both transports`() = runTest {
        val (s, _) = selector()
        reachable = false
        gatewayConnects = false
        network.emit(Unit)
        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        assertEquals(1, gateways.size)
        assertFalse(directs[0].closed, "direct is still connected, so the switch waits")
        s.close()
        runCurrent()
        assertTrue(directs[0].closed)
        assertTrue(gateways[0].closed)
    }
}
