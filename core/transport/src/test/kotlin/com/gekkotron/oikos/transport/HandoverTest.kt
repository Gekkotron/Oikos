package com.gekkotron.oikos.transport

import com.gekkotron.oikos.transport.testing.FakeManagedTransport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class HandoverTest {
    private fun connecting() = FakeManagedTransport(TransportSpec.Demo).also { it.fake.setLink(LinkState.Connecting) }
    private fun connected() = FakeManagedTransport(TransportSpec.Demo) // FakeMqttTransport starts Connected(DIRECT)

    private fun TestScope.handover(): Pair<Handover, MutableList<String>> {
        val h = Handover(backgroundScope, connectTimeout = 15.seconds)
        val got = mutableListOf<String>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { h.messages.collect { got += it.payloadString() } }
        return h to got
    }

    @Test
    fun `with no current transport the first one becomes current at once`() = runTest {
        val (h, _) = handover()
        val first = connecting()
        assertTrue(h.switchTo(first))
        assertTrue(first.started)
        assertSame(first, h.current.value)
        assertEquals(LinkState.Connecting, h.link.value)
        first.fake.setLink(LinkState.Reconnecting(3))
        runCurrent()
        assertEquals(LinkState.Reconnecting(3), h.link.value)
    }

    @Test
    fun `a connected current waits for the next to connect, then nothing from the old is emitted`() = runTest {
        val (h, got) = handover()
        h.setSubscriptions(setOf("#"))
        val old = connected()
        h.switchTo(old)
        val next = connecting()
        val switched = async { h.switchTo(next) }
        runCurrent()
        assertEquals(setOf("#"), next.fake.subscriptions, "subscriptions are applied to the pending transport")
        old.fake.deliver("t", "old-1")
        next.fake.deliver("t", "new-1") // buffered: next is not current yet
        runCurrent()
        assertEquals(listOf("old-1"), got)
        assertSame(old, h.current.value)
        next.fake.setLink(LinkState.Connected(TransportMode.GATEWAY))
        runCurrent()
        assertTrue(switched.await())
        old.fake.deliver("t", "old-2")
        next.fake.deliver("t", "new-2")
        runCurrent()
        assertEquals(listOf("old-1", "new-1", "new-2"), got)
        assertTrue(old.closed)
        assertSame(next, h.current.value)
        assertEquals(LinkState.Connected(TransportMode.GATEWAY), h.link.value)
    }

    @Test
    fun `a current that is not connected is replaced at once`() = runTest {
        val (h, _) = handover()
        val old = connected()
        h.switchTo(old)
        old.fake.setLink(LinkState.Reconnecting(1))
        val next = connecting()
        assertTrue(h.switchTo(next))
        assertTrue(old.closed)
        assertSame(next, h.current.value)
    }

    @Test
    fun `a next that never connects is closed and the current kept`() = runTest {
        val (h, _) = handover()
        val old = connected()
        h.switchTo(old)
        val next = connecting()
        val switched = async { h.switchTo(next) }
        advanceTimeBy(15.seconds + 1.seconds)
        assertFalse(switched.await())
        assertTrue(next.closed)
        assertFalse(old.closed)
        assertSame(old, h.current.value)
    }

    @Test
    fun `a next whose start throws is closed and the current kept`() = runTest {
        val (h, _) = handover()
        val old = connected()
        h.switchTo(old)
        val broken = FakeManagedTransport(TransportSpec.Demo, failStart = true)
        assertFalse(h.switchTo(broken))
        assertTrue(broken.closed)
        assertSame(old, h.current.value)
    }

    @Test
    fun `subscriptions reach the current transport`() = runTest {
        val (h, _) = handover()
        val t = connected()
        h.switchTo(t)
        h.setSubscriptions(setOf("a/#"))
        assertEquals(setOf("a/#"), t.fake.subscriptions)
    }

    @Test
    fun `publish needs a current transport`() = runTest {
        val (h, _) = handover()
        assertFailsWith<PublishFailed> { h.publish("t", byteArrayOf(1), 1, false) }
        val t = connected()
        h.switchTo(t)
        h.publish("t", "x".encodeToByteArray(), 1, false)
        assertEquals(listOf("t" to "x"), t.fake.publishedStrings())
    }

    @Test
    fun `close during a pending switch closes both transports`() = runTest {
        val (h, _) = handover()
        val old = connected()
        h.switchTo(old)
        val next = connecting()
        val switched = async { h.switchTo(next) }
        runCurrent()
        h.close()
        runCurrent()
        assertFalse(switched.await())
        assertTrue(old.closed)
        assertTrue(next.closed)
        assertNull(h.current.value)
        assertEquals(LinkState.Offline(LinkState.Offline.CLOSED), h.link.value)
        assertFalse(h.switchTo(connected()), "a closed handover accepts nothing")
    }
}
