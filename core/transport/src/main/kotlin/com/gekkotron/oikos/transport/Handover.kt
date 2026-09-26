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
import kotlinx.coroutines.flow.filterNotNull
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

    /** @return true once [next] is current; false if it failed to start, went Offline or did not connect in time, or [close] won. */
    suspend fun switchTo(next: ManagedTransport): Boolean = switching.withLock {
        val inbox = Channel<MqttMessage>(Channel.UNLIMITED)
        // Undispatched: subscribed before start(), so the first retained message is already buffered.
        // trySend, not send: once dispose() closes this channel, a straggling emission must not throw
        // ClosedSendChannelException (UNLIMITED never fails to accept otherwise).
        val intake = scope.launch(start = CoroutineStart.UNDISPATCHED) { next.messages().collect { inbox.trySend(it) } }
        val slot = Slot(next, inbox, intake)
        var flipped = false
        try {
            val admitted = lock.withLock {
                if (!closed.value) pending = slot
                !closed.value
            }
            if (!admitted) return false
            next.start()
            // close() may have raced us here and already disposed this (still unstarted) slot; the
            // transport is now actually running and must be closed again now there is something to stop.
            val stillOpen = lock.withLock { !closed.value }
            if (!stillOpen) {
                closeQuietly(next)
                return false
            }
            applySubscriptions(next)
            val old = lock.withLock { active }
            val ready = old == null || old.transport.link.value !is LinkState.Connected || awaitConnected(next, old.transport)
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

    /**
     * The caller could not even build a transport to switch to (a factory threw). With nothing current the
     * link would otherwise stay `Connecting` forever; a current transport, or a closed handover, keeps its
     * own state. The next successful [switchTo] replaces this.
     */
    suspend fun failIfIdle() {
        lock.withLock {
            if (!closed.value && active == null) _link.value = LinkState.Offline(LinkState.Offline.FAILED)
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
                // Set together with `current` becoming null: no window where a collector observes
                // current == null while link still reports the old (now-meaningless) state.
                _current.value = null
                _link.value = LinkState.Offline(LinkState.Offline.CLOSED)
            }
        }
        // Never cancelled halfway: every slot we took from `active`/`pending` above must end up closed.
        withContext(NonCancellable) {
            slots.forEach { dispose(it) }
        }
    }

    /**
     * Also completes once [old] itself leaves `Connected`: no point waiting out the timeout for [next]
     * to connect while the transport we would keep is already dead. And it gives up at once (false) when
     * [next] goes `Offline`: [next] has already been started, so `Offline` is final for it (a dropped or
     * refused gateway stream, rejected direct credentials) and waiting out the timeout would only stall.
     */
    private suspend fun awaitConnected(next: ManagedTransport, old: ManagedTransport): Boolean =
        withTimeoutOrNull(connectTimeout) {
            combine(next.link, closed, old.link) { link, isClosed, oldLink ->
                when {
                    isClosed -> true // flip() sees `closed` and declines
                    link is LinkState.Offline -> false
                    link is LinkState.Connected || oldLink !is LinkState.Connected -> true
                    else -> null
                }
            }.filterNotNull().first()
        } ?: false

    /** Re-applies when [setSubscriptions] ran meanwhile, so [next] never ends up on an older set. */
    private suspend fun applySubscriptions(next: ManagedTransport) {
        while (true) {
            val (seen, filters) = lock.withLock { version to subscriptions }
            if (filters.isNotEmpty()) next.setSubscriptions(filters)
            if (lock.withLock { version } == seen) return
        }
    }

    /**
     * Once committed (`active = slot`), this must run to completion regardless of the caller being
     * cancelled: a caller cancelled mid-flip must not leave `active`/`current` pointing at a slot whose
     * old counterpart (or whose own [dispose] in the finally below) was only half torn down.
     */
    private suspend fun flip(slot: Slot, old: Slot?): Boolean = withContext(NonCancellable) {
        val committed = lock.withLock {
            if (closed.value) {
                false
            } else {
                pending = null
                active = slot
                // Stop the old transport's forwarding before the new one starts: no straggler after the flip.
                old?.forward?.cancelAndJoin()
                old?.mirror?.cancelAndJoin()
                slot.mirror = scope.launch(start = CoroutineStart.UNDISPATCHED) { slot.transport.link.collect { _link.value = it } }
                slot.forward = scope.launch(start = CoroutineStart.UNDISPATCHED) { for (m in slot.inbox) out.emit(m) }
                _current.value = slot.transport
                true
            }
        }
        if (committed) old?.let { dispose(it) }
        committed
    }

    private suspend fun dispose(slot: Slot) {
        if (!slot.disposed.compareAndSet(false, true)) return
        slot.intake.cancel()
        slot.forward?.cancel()
        slot.mirror?.cancel()
        slot.inbox.close()
        closeQuietly(slot.transport)
    }

    private suspend fun closeQuietly(transport: ManagedTransport) {
        try {
            transport.close()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // close() is contractually quiet; never let one transport block the next.
        }
    }
}
