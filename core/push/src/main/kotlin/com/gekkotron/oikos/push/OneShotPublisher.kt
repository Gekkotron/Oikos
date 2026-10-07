package com.gekkotron.oikos.push

import com.gekkotron.oikos.transport.DirectMqttTransport
import com.gekkotron.oikos.transport.GatewayApi
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.LocalProbe
import com.gekkotron.oikos.transport.ManagedTransport
import com.gekkotron.oikos.transport.MqttTransport
import com.gekkotron.oikos.transport.PublishFailed
import com.gekkotron.oikos.transport.Route
import com.gekkotron.oikos.transport.TcpProbe
import com.gekkotron.oikos.transport.TransportSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun interface TopicPublisher {
    suspend fun publish(topic: String, payload: ByteArray, retain: Boolean): Result<Unit>
}

class NotConfigured : Exception("no connection profile")

/**
 * Publishes one message from the background, without a `TransportSelector` (push alerts spec §5.3). Order: the live
 * transport when connected, then the stored profile's route. Always QoS 1.
 */
class OneShotPublisher(
    private val live: MqttTransport,
    private val specs: suspend () -> TransportSpec?,
    private val probe: (TransportSpec.Direct) -> LocalProbe = { TcpProbe(it.host, it.port) },
    private val directTransport: (TransportSpec.Direct, CoroutineScope) -> ManagedTransport = { spec, scope -> DirectMqttTransport(spec, scope) },
    private val gatewayApi: (TransportSpec.Gateway) -> GatewayApi,
    private val directBudget: Duration = 10.seconds,
) : TopicPublisher {
    override suspend fun publish(topic: String, payload: ByteArray, retain: Boolean): Result<Unit> {
        if (live.link.value is LinkState.Connected && attempt { live.publish(topic, payload, QOS, retain) }.isSuccess) return Result.success(Unit)
        return when (val spec = specs()) {
            null, TransportSpec.Demo -> Result.failure(NotConfigured())
            is TransportSpec.Direct -> viaDirect(spec, topic, payload, retain)
            is TransportSpec.Gateway -> viaGateway(spec, topic, payload, retain)
            is TransportSpec.Routed -> when (spec.route) {
                Route.DIRECT -> viaDirect(spec.direct, topic, payload, retain)
                Route.GATEWAY -> viaGateway(spec.gateway, topic, payload, retain)
                Route.AUTO -> routedAuto(spec, topic, payload, retain)
            }
        }
    }

    private suspend fun routedAuto(spec: TransportSpec.Routed, topic: String, payload: ByteArray, retain: Boolean): Result<Unit> {
        if (probe(spec.direct).reachable()) {
            val viaDirect = viaDirect(spec.direct, topic, payload, retain)
            if (viaDirect.isSuccess) return viaDirect
        }
        return viaGateway(spec.gateway, topic, payload, retain)
    }

    private suspend fun viaGateway(spec: TransportSpec.Gateway, topic: String, payload: ByteArray, retain: Boolean): Result<Unit> =
        attempt { gatewayApi(spec).publish(topic, payload, QOS, retain) }

    private suspend fun viaDirect(spec: TransportSpec.Direct, topic: String, payload: ByteArray, retain: Boolean): Result<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = directTransport(spec, scope)
        try {
            transport.start()
            val state = withTimeoutOrNull(directBudget) {
                transport.link.first { it is LinkState.Connected || (it is LinkState.Offline && it.reason == LinkState.Offline.REJECTED) }
            }
            return when {
                state is LinkState.Connected -> attempt { transport.publish(topic, payload, QOS, retain) }
                state == null -> Result.failure(PublishFailed("the broker did not answer in time"))
                else -> Result.failure(PublishFailed("the broker rejected the credentials"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            withContext(NonCancellable) {
                try {
                    transport.close()
                } catch (e: Exception) {
                    // close() is contractually quiet; a failure here must not hide the publish result.
                }
                scope.cancel()
            }
        }
    }

    private suspend fun attempt(block: suspend () -> Unit): Result<Unit> = try {
        block()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private companion object {
        const val QOS = 1
    }
}
