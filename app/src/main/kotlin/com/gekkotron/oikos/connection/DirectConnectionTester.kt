package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.feature.onboarding.ConnectionTester
import com.gekkotron.oikos.feature.onboarding.TestFailure
import com.gekkotron.oikos.feature.onboarding.TestState
import com.gekkotron.oikos.model.Device
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.source.MqttDiscoverySource
import com.gekkotron.oikos.transport.DirectFailure
import com.gekkotron.oikos.transport.AthenaGatewayApi
import com.gekkotron.oikos.transport.DirectMqttTransport
import com.gekkotron.oikos.transport.GatewayApi
import com.gekkotron.oikos.transport.GatewayTransport
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Onboarding's connection test (spec §5.1). It uses a temporary transport and discovery source, so the
 * device count comes from the real parser. When the broker is out of reach and the code has a gateway,
 * it tries the gateway the same way (gateway spec §5). Cancelling the flow closes the connection.
 */
class DirectConnectionTester(
    private val clientId: suspend () -> String,
    private val connectTimeout: Duration = 5.seconds,
    private val gatewayTimeout: Duration = 10.seconds,
    private val gatewayApi: (TransportSpec.Gateway) -> GatewayApi = { AthenaGatewayApi(it) },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ConnectionTester {
    override fun test(draft: ConnectionProfile.Direct): Flow<TestState> = channelFlow {
        send(TestState.Connecting)
        val failure = testDirect(draft) ?: return@channelFlow // null: connected, discovered until cancelled
        val gateway = draft.gateway
        if (failure != TestFailure.Unreachable || gateway == null) {
            send(TestState.Failed(failure))
            return@channelFlow
        }
        // A child scope: a failed attempt must not leave the source's collectors keeping this flow open.
        val attempt = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val transport = GatewayTransport(gatewayApi(gateway.toSpec(draft.local)), attempt)
        try {
            transport.start()
            // The source's subscriptions open the stream: the gateway refuses an empty topic list.
            val source = MqttDiscoverySource(transport, attempt, prefix = draft.prefix)
            val link = withTimeoutOrNull(gatewayTimeout) {
                transport.link.first { it is LinkState.Connected || it is LinkState.Offline }
            }
            if (link !is LinkState.Connected) {
                send(TestState.Failed(gatewayFailure(link)))
                return@channelFlow
            }
            discover(source, viaGateway = true)
        } finally {
            withContext(NonCancellable) { transport.close() }
            attempt.cancel()
        }
    }.flowOn(dispatcher)

    /** Returns the failure, or runs discovery until the collector cancels (and so never returns). */
    private suspend fun ProducerScope<TestState>.testDirect(draft: ConnectionProfile.Direct): TestFailure? {
        val transport = DirectMqttTransport(draft.local.toSpec(clientId()), this)
        try {
            transport.start()
            // null = still trying; true = connected; a failure = definitive (no point waiting for the timeout).
            val outcome = withTimeoutOrNull(connectTimeout) {
                combine(transport.link, transport.failure) { link, failure ->
                    when {
                        link is LinkState.Connected -> true
                        failure == DirectFailure.Rejected || failure is DirectFailure.Tls -> failure
                        else -> null
                    }
                }.first { it != null }
            }
            if (outcome != true) return (outcome as? DirectFailure ?: transport.failure.value).toTestFailure()
            discover(MqttDiscoverySource(transport, this, prefix = draft.prefix), viaGateway = false)
            return null
        } finally {
            withContext(NonCancellable) { transport.close() }
        }
    }

    /**
     * conflate() keeps only the latest pending list while this collector is delaying, so a burst of retained
     * configs never grows the UI update rate past CONNECTED_THROTTLE, and the delay after send (rather than
     * a fixed-interval sample) guarantees the final list is always the one delivered.
     */
    private suspend fun ProducerScope<TestState>.discover(source: MqttDiscoverySource, viaGateway: Boolean) {
        combine(source.devices, source.entities, ::deviceNames)
            .distinctUntilChanged()
            .conflate()
            .collect { names ->
                send(TestState.Connected(names, viaGateway))
                delay(CONNECTED_THROTTLE)
            }
    }

    private companion object {
        val CONNECTED_THROTTLE = 250.milliseconds
    }
}

/** Device names, plus entities that belong to no known device; sorted case-insensitively. */
internal fun deviceNames(devices: Map<String, Device>, entities: Map<String, Entity>): List<String> {
    val loose = entities.values.filter { it.deviceId == null || it.deviceId !in devices }.map { it.name }
    return (devices.values.map { it.name } + loose).sortedBy { it.lowercase() }
}

internal fun DirectFailure?.toTestFailure(): TestFailure = when (this) {
    null, DirectFailure.Unreachable -> TestFailure.Unreachable
    DirectFailure.Rejected -> TestFailure.Rejected
    is DirectFailure.Tls -> TestFailure.Tls
    is DirectFailure.Other -> TestFailure.Other(message)
}

/** A gateway attempt that did not connect: its refusal, or "unreachable" when it simply never connected. */
internal fun gatewayFailure(link: LinkState?): TestFailure = when ((link as? LinkState.Offline)?.reason) {
    LinkState.Offline.KEY_REJECTED -> TestFailure.KeyRejected
    LinkState.Offline.CLOCK_SKEW -> TestFailure.ClockSkew
    LinkState.Offline.REJECTED -> TestFailure.Rejected
    LinkState.Offline.GATEWAY_REFUSED -> TestFailure.Other("gateway refused")
    else -> TestFailure.Unreachable
}
