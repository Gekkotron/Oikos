package com.gekkotron.oikos.transport

import com.gekkotron.oikos.transport.testing.FakeManagedTransport
import com.gekkotron.oikos.transport.testing.FakeMqttTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

    /** A [ManagedTransport] whose [close] blocks until the test releases [closeGate]. */
    private class SlowCloseTransport(private val fake: FakeMqttTransport = FakeMqttTransport()) : ManagedTransport {
        val closeGate = CompletableDeferred<Unit>()
        var closed = false
            private set

        override val link: StateFlow<LinkState> get() = fake.link

        override fun messages(): SharedFlow<MqttMessage> = fake.messages()

        override suspend fun setSubscriptions(filters: Set<String>) = fake.setSubscriptions(filters)

        override suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean) =
            fake.publish(topic, payload, qos, retain)

        override fun start() = Unit

        override suspend fun close() {
            closeGate.await()
            closed = true
        }
    }

    @Test
    fun `flip finishes disposing the old transport even if the caller is cancelled mid-flip`() = runTest {
        val (h, _) = handover()
        val old = SlowCloseTransport()
        h.switchTo(old)
        val next = connecting()
        val switched = async { h.switchTo(next) }
        runCurrent()
        next.fake.setLink(LinkState.Connected(TransportMode.GATEWAY))
        runCurrent()
        // The flip already committed `next` as current and is now suspended disposing `old` (NonCancellable).
        assertSame(next, h.current.value)
        switched.cancel()
        runCurrent()
        assertFalse(old.closed, "still disposing old, unaffected by the caller's own cancellation")
        assertFalse(next.closed, "the survivor must not be torn down by the cancelled caller's cleanup")
        old.closeGate.complete(Unit)
        runCurrent()
        assertTrue(old.closed)
        assertSame(next, h.current.value)
        assertFalse(next.closed)
        h.close()
        runCurrent()
        assertTrue(next.closed)
    }

    /**
     * Simulates [close] winning a race against a pending [switchTo]: the handover is fully closed
     * (disposing this still-unstarted slot) before [start] returns control, then [start] proceeds anyway.
     */
    private class SelfClosingOnStart(private val handoverRef: () -> Handover) : ManagedTransport {
        private val fake = FakeMqttTransport()
        val calls = mutableListOf<String>()

        override val link: StateFlow<LinkState> get() = fake.link

        override fun messages(): SharedFlow<MqttMessage> = fake.messages()

        override suspend fun setSubscriptions(filters: Set<String>) = fake.setSubscriptions(filters)

        override suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean) =
            fake.publish(topic, payload, qos, retain)

        override fun start() {
            runBlocking { handoverRef().close() }
            calls += "start"
        }

        override suspend fun close() {
            calls += "close"
        }
    }

    @Test
    fun `a transport closed before it started is closed again once it actually starts`() = runTest {
        val h = Handover(backgroundScope, connectTimeout = 15.seconds)
        val next = SelfClosingOnStart { h }
        assertFalse(h.switchTo(next))
        assertEquals(listOf("close", "start", "close"), next.calls)
    }

    @Test
    fun `the old leaving Connected during the wait flips at once instead of waiting out the timeout`() = runTest {
        val (h, _) = handover()
        val old = connected()
        h.switchTo(old)
        val next = connecting()
        val switched = async { h.switchTo(next) }
        runCurrent()
        old.fake.setLink(LinkState.Reconnecting(1))
        runCurrent()
        assertTrue(switched.isCompleted, "should flip immediately once the old leaves Connected, without waiting out connectTimeout")
        assertTrue(switched.await())
        assertSame(next, h.current.value)
        assertTrue(old.closed)
    }

    @Test
    fun `close sets Offline together with current becoming null, not only once the transport finishes closing`() = runTest {
        val (h, _) = handover()
        val slow = SlowCloseTransport()
        h.switchTo(slow)
        val closing = launch { h.close() }
        runCurrent()
        // close() is now suspended disposing `slow` (closeGate not yet released).
        assertNull(h.current.value)
        assertEquals(LinkState.Offline(LinkState.Offline.CLOSED), h.link.value)
        slow.closeGate.complete(Unit)
        runCurrent()
        closing.join()
    }
}
