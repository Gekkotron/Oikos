package com.gekkotron.oikos.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
 * until the next network change — even a direct candidate rejected before it ever becomes current (still
 * racing the gateway inside a pending [Handover.switchTo]) is marked rejected. A bad probe or transport
 * factory during one check is swallowed; the selector keeps whatever is current (Offline(FAILED) when
 * nothing is) and retries on the next trigger; on a fixed route a throwing factory leaves Offline(FAILED).
 * The foreground lifecycle stays with [ActiveTransport].
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

    /** The latest direct transport [newDirect] created, whether or not [Handover] ever made it current. */
    private val directNowFlow = MutableStateFlow<ManagedTransport?>(null)

    @Volatile
    private var directRejected = false
    private var job: Job? = null

    override fun messages(): SharedFlow<MqttMessage> = handover.messages

    override fun start() {
        check(job == null) { "already started" }
        job = scope.launch {
            when (spec.route) {
                Route.DIRECT -> fixed { newDirect() }
                Route.GATEWAY -> fixed { gateway(spec.gateway) }
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

    /**
     * A fixed route has no other transport to fall back to: a factory that throws (a malformed gateway URL
     * in a saved profile makes the SDK throw IllegalArgumentException) leaves the link Offline(FAILED)
     * instead of crashing the process on every launch. There is no retry: the profile needs fixing.
     */
    private suspend fun fixed(create: () -> ManagedTransport) {
        val transport = try {
            create()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handover.failIfIdle()
            return
        }
        handover.switchTo(transport)
    }

    private fun newDirect(): ManagedTransport = direct(spec.direct).also { directNowFlow.value = it }

    private fun onDirect(): Boolean = handover.current.value.let { it != null && it === directNowFlow.value }

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
            // Watches the latest direct candidate itself, not handover.current: a candidate can be
            // rejected while still pending inside a switchTo race (the gateway is still current) or
            // after losing that race, and that must still stick until the next network change.
            directNowFlow.collectLatest { candidate ->
                if (candidate == null) return@collectLatest
                candidate.link.collectLatest { link ->
                    when {
                        link is LinkState.Offline && link.reason == LinkState.Offline.REJECTED -> {
                            directRejected = true
                            checks.send(Unit)
                        }
                        link is LinkState.Reconnecting && candidate === handover.current.value -> {
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
        for (trigger in checks) {
            try {
                val wantDirect = !directRejected && probe.reachable()
                if (handover.current.value == null || wantDirect != onDirect()) {
                    handover.switchTo(if (wantDirect) newDirect() else gateway(spec.gateway))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A bad probe or transport factory must not kill the loop: keep whatever is current
                // and retry on the next trigger (network change, re-probe, or a link-state change).
                // With nothing current yet, say so rather than showing Connecting forever.
                handover.failIfIdle()
            }
        }
    }
}
