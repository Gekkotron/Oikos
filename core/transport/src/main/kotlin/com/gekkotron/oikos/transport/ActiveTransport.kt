package com.gekkotron.oikos.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The one [MqttTransport] the rest of the app sees (spec §3.3). It runs the transport for the current
 * [TransportSpec], replays subscriptions onto each new one, and pauses [backgroundGrace] after the app
 * leaves the foreground. Switching is break-before-make; Plan 2's selector adds make-before-break.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveTransport(
    specs: Flow<TransportSpec?>,
    private val factory: (TransportSpec) -> ManagedTransport,
    private val scope: CoroutineScope,
    backgroundGrace: Duration = 30.seconds,
) : MqttTransport {
    private val _link = MutableStateFlow<LinkState>(LinkState.Offline(LinkState.Offline.NOT_CONFIGURED))
    override val link: StateFlow<LinkState> = _link.asStateFlow()

    private val out = MutableSharedFlow<MqttMessage>(extraBufferCapacity = 4096)
    private val foreground = MutableStateFlow(true)
    private val mutex = Mutex()
    private var subscriptions: Set<String> = emptySet()
    private var current: Running? = null

    private class Running(val transport: ManagedTransport, val jobs: List<Job>)

    private sealed interface Target {
        data object Paused : Target
        data object NotConfigured : Target
        data class Run(val spec: TransportSpec) : Target
    }

    init {
        val active = foreground.transformLatest { isForeground ->
            if (!isForeground) delay(backgroundGrace)
            emit(isForeground)
        }
        scope.launch {
            combine(specs, active) { spec, isActive ->
                when {
                    !isActive -> Target.Paused
                    spec == null -> Target.NotConfigured
                    else -> Target.Run(spec)
                }
            }.distinctUntilChanged().collect { apply(it) }
        }
    }

    override fun messages(): SharedFlow<MqttMessage> = out.asSharedFlow()

    /** Driven by the process lifecycle. Going to the background pauses after the grace period. */
    fun setForeground(value: Boolean) {
        foreground.value = value
    }

    override suspend fun setSubscriptions(filters: Set<String>) {
        val transport = mutex.withLock {
            subscriptions = filters
            current?.transport
        }
        transport?.setSubscriptions(filters)
    }

    override suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean) {
        val transport = mutex.withLock { current?.transport } ?: throw PublishFailed("not connected")
        transport.publish(topic, payload, qos, retain)
    }

    private suspend fun apply(target: Target) {
        val old = mutex.withLock { current.also { current = null } }
        if (old != null) {
            // Join, not just cancel: a collector mid-emit must not push a straggler message (or link
            // state) from the old transport after the new one has started.
            old.jobs.forEach { it.cancel() }
            old.jobs.joinAll()
            try {
                old.transport.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // close() is contractually quiet (spec §3.2); a failure here must not stop the
                // pipeline from reacting to the next spec change.
            }
        }
        when (target) {
            Target.Paused -> _link.value = LinkState.Offline(LinkState.Offline.PAUSED)
            Target.NotConfigured -> _link.value = LinkState.Offline(LinkState.Offline.NOT_CONFIGURED)
            is Target.Run -> start(target.spec)
        }
    }

    private suspend fun start(spec: TransportSpec) {
        val transport = factory(spec)
        // Undispatched so both collectors are subscribed before start() or a replay can deliver anything.
        val jobs = listOf(
            scope.launch(start = CoroutineStart.UNDISPATCHED) { transport.messages().collect { out.emit(it) } },
            scope.launch(start = CoroutineStart.UNDISPATCHED) { transport.link.collect { _link.value = it } },
        )
        val filters = mutex.withLock {
            current = Running(transport, jobs)
            subscriptions
        }
        try {
            transport.start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // start() failed: surface it as offline but keep the pipeline alive so the next spec
            // change (or a later retry) can still run.
            _link.value = LinkState.Offline(LinkState.Offline.CLOSED)
            return
        }
        if (filters.isNotEmpty()) transport.setSubscriptions(filters)
    }
}
