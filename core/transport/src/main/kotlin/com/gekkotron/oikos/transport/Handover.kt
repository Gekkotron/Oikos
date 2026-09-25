package com.gekkotron.oikos.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs one [ManagedTransport] as current and hands over to the next make-before-break (gateway spec §3.2).
 * The next transport's messages are buffered from its very first one, so after the flip every topic ends
 * at its latest value, and nothing from the old transport is emitted once the flip has happened.
 */
internal class Handover(
    private val scope: CoroutineScope,
    private val connectTimeout: Duration = 15.seconds,
) {
    private val _link = MutableStateFlow<LinkState>(LinkState.Connecting)
    val link: StateFlow<LinkState> = _link.asStateFlow()

    private val out = MutableSharedFlow<MqttMessage>(extraBufferCapacity = 4096)
    val messages: SharedFlow<MqttMessage> = out.asSharedFlow()

    private val _current = MutableStateFlow<ManagedTransport?>(null)
    val current: StateFlow<ManagedTransport?> = _current.asStateFlow()

    private class Slot(val transport: ManagedTransport, val inbox: Channel<MqttMessage>, val intake: Job) {
        var forward: Job? = null
        var mirror: Job? = null
        val disposed = AtomicBoolean(false)
    }

    private val switching = Mutex() // one switch at a time
    private val lock = Mutex() // slots, subscriptions; never held while calling into a transport
    private var active: Slot? = null
    private var pending: Slot? = null
    private var subscriptions: Set<String> = emptySet()
    private var version = 0L
    private val closed = MutableStateFlow(false)

    /** @return true once [next] is current; false if it failed to start, did not connect in time, or [close] won. */
    suspend fun switchTo(next: ManagedTransport): Boolean = switching.withLock {
        val inbox = Channel<MqttMessage>(Channel.UNLIMITED)
        // Undispatched: subscribed before start(), so the first retained message is already buffered.
        val intake = scope.launch(start = CoroutineStart.UNDISPATCHED) { next.messages().collect { inbox.send(it) } }
        val slot = Slot(next, inbox, intake)
        var flipped = false
        try {
            val admitted = lock.withLock {
                if (!closed.value) pending = slot
                !closed.value
            }
            if (!admitted) return false
            next.start()
            applySubscriptions(next)
            val old = lock.withLock { active }
            val ready = old == null || old.transport.link.value !is LinkState.Connected || awaitConnected(next)
            if (ready) flipped = flip(slot, old)
            flipped
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false // start() or setSubscriptions() threw: keep what we have
        } finally {
            if (!flipped) {
                withContext(NonCancellable) {
                    lock.withLock { if (pending === slot) pending = null }
                    dispose(slot)
                }
            }
        }
    }

    suspend fun setSubscriptions(filters: Set<String>) {
        val targets = lock.withLock {
            subscriptions = filters
            version++
            listOfNotNull(active?.transport, pending?.transport)
        }
        targets.forEach { it.setSubscriptions(filters) }
    }

    suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean) {
        val transport = lock.withLock { active?.transport } ?: throw PublishFailed("not connected")
        transport.publish(topic, payload, qos, retain)
    }

    suspend fun close() {
        val slots = lock.withLock {
            closed.value = true
            listOfNotNull(active, pending).also {
                active = null
                pending = null
                _current.value = null
            }
        }
        slots.forEach { dispose(it) }
        _link.value = LinkState.Offline(LinkState.Offline.CLOSED)
    }

    private suspend fun awaitConnected(next: ManagedTransport): Boolean =
        withTimeoutOrNull(connectTimeout) {
            combine(next.link, closed) { link, isClosed -> isClosed || link is LinkState.Connected }.first { it }
        } != null

    /** Re-applies when [setSubscriptions] ran meanwhile, so [next] never ends up on an older set. */
    private suspend fun applySubscriptions(next: ManagedTransport) {
        while (true) {
            val (seen, filters) = lock.withLock { version to subscriptions }
            if (filters.isNotEmpty()) next.setSubscriptions(filters)
            if (lock.withLock { version } == seen) return
        }
    }

    private suspend fun flip(slot: Slot, old: Slot?): Boolean {
        lock.withLock {
            if (closed.value) return false
            pending = null
            active = slot
            // Stop the old transport's forwarding before the new one starts: no straggler after the flip.
            old?.forward?.cancelAndJoin()
            old?.mirror?.cancelAndJoin()
            slot.mirror = scope.launch(start = CoroutineStart.UNDISPATCHED) { slot.transport.link.collect { _link.value = it } }
            slot.forward = scope.launch(start = CoroutineStart.UNDISPATCHED) { for (m in slot.inbox) out.emit(m) }
            _current.value = slot.transport
        }
        old?.let { dispose(it) }
        return true
    }

    private suspend fun dispose(slot: Slot) {
        if (!slot.disposed.compareAndSet(false, true)) return
        slot.intake.cancel()
        slot.forward?.cancel()
        slot.mirror?.cancel()
        slot.inbox.close()
        try {
            slot.transport.close()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // close() is contractually quiet; never let one transport block the next.
        }
    }
}
