package com.gekkotron.oikos.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Direct at home, the gateway elsewhere (gateway spec §3.5). With [Route.AUTO] it re-evaluates on start,
 * on network changes, when the direct link starts reconnecting, and every [reprobeInterval] while not on
 * direct; a switch is make-before-break through [Handover]. Rejected direct credentials stay on the gateway
 * until the next network change. The foreground lifecycle stays with [ActiveTransport].
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class TransportSelector(
    private val spec: TransportSpec.Routed,
    private val direct: (TransportSpec.Direct) -> ManagedTransport,
    private val gateway: (TransportSpec.Gateway) -> ManagedTransport,
    private val probe: LocalProbe,
    private val networkChanges: Flow<Unit>,
    private val scope: CoroutineScope,
    private val reprobeInterval: Duration = 60.seconds,
    private val networkDebounce: Duration = 1.seconds,
    private val reconnectingDebounce: Duration = 2.seconds,
    switchTimeout: Duration = 15.seconds,
) : ManagedTransport {
    private val handover = Handover(scope, switchTimeout)
    override val link: StateFlow<LinkState> = handover.link

    @Volatile
    private var directNow: ManagedTransport? = null

    @Volatile
    private var directRejected = false
    private var job: Job? = null

    override fun messages(): SharedFlow<MqttMessage> = handover.messages

    override fun start() {
        check(job == null) { "already started" }
        job = scope.launch {
            when (spec.route) {
                Route.DIRECT -> handover.switchTo(newDirect())
                Route.GATEWAY -> handover.switchTo(gateway(spec.gateway))
                Route.AUTO -> auto()
            }
        }
    }

    override suspend fun close() {
        job?.cancelAndJoin()
        handover.close()
    }

    override suspend fun setSubscriptions(filters: Set<String>) = handover.setSubscriptions(filters)

    override suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean) =
        handover.publish(topic, payload, qos, retain)

    private fun newDirect(): ManagedTransport = direct(spec.direct).also { directNow = it }

    private fun onDirect(): Boolean = handover.current.value.let { it != null && it === directNow }

    private suspend fun auto() = coroutineScope {
        val checks = Channel<Unit>(Channel.CONFLATED)
        checks.trySend(Unit)
        launch {
            networkChanges.debounce(networkDebounce).collect {
                directRejected = false
                checks.send(Unit)
            }
        }
        launch {
            handover.current.collectLatest { current ->
                if (current == null || current !== directNow) return@collectLatest
                current.link.collectLatest { link ->
                    when {
                        link is LinkState.Offline && link.reason == LinkState.Offline.REJECTED -> {
                            directRejected = true
                            checks.send(Unit)
                        }
                        link is LinkState.Reconnecting -> {
                            delay(reconnectingDebounce) // a new attempt restarts the wait
                            checks.send(Unit)
                        }
                    }
                }
            }
        }
        launch {
            while (true) {
                delay(reprobeInterval) // a Wi-Fi join is not always a new default network
                if (!onDirect()) checks.send(Unit)
            }
        }
        for (check in checks) {
            val wantDirect = !directRejected && probe.reachable()
            if (handover.current.value == null || wantDirect != onDirect()) {
                handover.switchTo(if (wantDirect) newDirect() else gateway(spec.gateway))
            }
        }
    }
}
