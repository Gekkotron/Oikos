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

    @Volatile
    private var stopped = false
    private val jobs = mutableListOf<Job>()

    override fun messages(): SharedFlow<MqttMessage> = handover.messages

    override fun start() {
        check(jobs.isEmpty()) { "already started" }
        jobs += scope.launch { combine(handover.link, overlay) { link, over -> over ?: link }.collect { _link.value = it } }
        jobs += scope.launch { resubscribe() }
        jobs += scope.launch { reconnect() }
    }

    override suspend fun close() {
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

    /** The gateway refuses an empty topic list, so nothing opens until the source subscribes. */
    private suspend fun resubscribe() {
        wanted.filter { it.isNotEmpty() }.debounce(resubscribeDebounce).collect { topics ->
            if (!stopped && topics != live.value?.topics) open(topics)
        }
    }

    private suspend fun reconnect() {
        var attempt = 0
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
                    is GatewayStream.End.Refused -> {
                        stopped = true
                        overlay.value = LinkState.Offline(end.reason)
                    }
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

    private suspend fun open(topics: Set<String>) {
        val stream = GatewayStream(api, topics, scope)
        if (handover.switchTo(stream)) live.value = stream
    }
}
