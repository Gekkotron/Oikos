package com.gekkotron.oikos.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The house through Athena-HttpMqttGateway (gateway spec §3.4). The gateway has no "add topic" call, so a
 * new subscription set opens a new stream make-before-break; a dead stream is reopened with backoff.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class GatewayTransport(
    private val api: GatewayApi,
    private val scope: CoroutineScope,
    private val backoff: Backoff = Backoff(),
    private val resubscribeDebounce: Duration = 300.milliseconds,
    private val stableAfter: Duration = 30.seconds,
    switchTimeout: Duration = 15.seconds,
) : ManagedTransport {
    private val handover = Handover(scope, switchTimeout)
    private val _link = MutableStateFlow<LinkState>(LinkState.Connecting)
    override val link: StateFlow<LinkState> = _link.asStateFlow()

    private val wanted = MutableStateFlow<Set<String>>(emptySet())
    private val live = MutableStateFlow<GatewayStream?>(null)

    /** Shown instead of the stream's own link: `Reconnecting(n)` while reopening, `Offline` after a refusal. */
    private val overlay = MutableStateFlow<LinkState?>(null)

    /** Reopen attempts since the live stream last stayed up; read by the link mapping in [start]. */
    @Volatile
    private var attempt = 0

    /** [resubscribe] and [reconnect] open one stream at a time, so both never open the same set. */
    private val opening = Mutex()

    @Volatile
    private var stopped = false

    /** Set by [close] only: a refusal stops reconnecting but must not unmask the stream's own CLOSED. */
    @Volatile
    private var closed = false
    private val jobs = mutableListOf<Job>()

    override fun messages(): SharedFlow<MqttMessage> = handover.messages

    override fun start() {
        check(jobs.isEmpty()) { "already started" }
        jobs += scope.launch { combine(handover.link, overlay, ::shown).collect { _link.value = it } }
        jobs += scope.launch { resubscribe() }
        jobs += scope.launch { reconnect() }
    }

    /**
     * A live stream that drops goes Offline(CLOSED) before [reconnect] has set its `Reconnecting` overlay,
     * and nothing orders the two on a multi-threaded dispatcher. Offline is final to an outer [Handover]
     * waiting on this transport, so that gap is shown as the reconnect it is until [close]; a refusal
     * shows its overlay instead. Keying this on [stopped] let a refusal leak a transient Offline(CLOSED)
     * when the link was mapped between `stopped = true` and the overlay being set.
     */
    private fun shown(link: LinkState, over: LinkState?): LinkState = when {
        over != null -> over
        !closed && link == LinkState.Offline(LinkState.Offline.CLOSED) -> LinkState.Reconnecting(attempt + 1)
        else -> link
    }

    override suspend fun close() {
        closed = true
        stopped = true
        jobs.forEach { it.cancelAndJoin() }
        handover.close()
        _link.value = LinkState.Offline(LinkState.Offline.CLOSED)
    }

    override suspend fun setSubscriptions(filters: Set<String>) {
        wanted.value = filters
    }

    override suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean) {
        if (_link.value !is LinkState.Connected) throw PublishFailed("not connected")
        api.publish(topic, payload, qos, retain)
    }

    /**
     * The gateway refuses an empty topic list, so nothing opens until the source subscribes. An incoming
     * stream that fails before it connects (dropped, or not connected within the switch timeout) is retried
     * with [backoff] until the set is live; the live stream keeps serving meanwhile, and its own reconnect
     * count (in [reconnect]) is left alone. A newer set supersedes a pending retry: the backoff wait ends as
     * soon as [wanted] moves on, and the collector then takes the newer set. A refusal is applied as for the
     * live stream (spec §3.4/§6).
     */
    private suspend fun resubscribe() {
        wanted.filter { it.isNotEmpty() }.debounce(resubscribeDebounce).collect { topics ->
            var retry = 0
            while (!stopped && topics != live.value?.topics) {
                val end = open(topics) ?: return@collect // live now
                if (stopped) return@collect // closed, or refused meanwhile
                if (end is GatewayStream.End.Refused) {
                    refuse(end.reason)
                    return@collect
                }
                retry++
                val superseded = withTimeoutOrNull(backoff.delayFor(retry)) { wanted.first { it != topics } } != null
                if (superseded) return@collect
            }
        }
    }

    /**
     * Refusal → Offline, no retry. The handover is closed too, which ends any stream still running (an
     * older topic set that still worked when a newer one was refused): the link says Offline, so nothing
     * must keep flowing or accept publishes behind it, and the gateway would refuse it on its next
     * reconnect anyway. Only a new transport (re-onboarding, a network change in the selector) tries again.
     */
    private suspend fun refuse(reason: String) {
        stopped = true
        overlay.value = LinkState.Offline(reason)
        handover.close()
    }

    private suspend fun reconnect() {
        live.filterNotNull().collectLatest { stream ->
            coroutineScope {
                val watch = launch {
                    stream.link.first { it is LinkState.Connected }
                    overlay.value = null
                    delay(stableAfter) // a stream that connects and drops at once must not reset the backoff
                    attempt = 0
                }
                val end = stream.end.await()
                watch.cancel()
                if (handover.current.value !== stream) return@coroutineScope // replaced by a new topic set, not dead
                when (end) {
                    is GatewayStream.End.Refused -> refuse(end.reason)
                    GatewayStream.End.Dropped -> {
                        attempt++
                        overlay.value = LinkState.Reconnecting(attempt)
                        delay(backoff.delayFor(attempt))
                        if (!stopped) open(wanted.first { it.isNotEmpty() })
                    }
                }
            }
        }
    }

    /**
     * @return null once a stream for [topics] is live, else how the new one ended (the handover has disposed
     * it). A caller that waited on [opening] finds the set already live when the other one just opened it.
     */
    private suspend fun open(topics: Set<String>): GatewayStream.End? = opening.withLock {
        live.value?.let { if (it.topics == topics && !it.end.isCompleted) return@withLock null }
        val stream = GatewayStream(api, topics, scope)
        if (handover.switchTo(stream)) {
            live.value = stream
            return@withLock null
        }
        stream.end.await()
    }
}
