package com.gekkotron.oikos.transport

import io.github.gekkotron.athena.gateway.client.AthenaGatewayClient
import io.github.gekkotron.athena.gateway.client.Broker
import io.github.gekkotron.athena.gateway.client.GatewayException
import io.github.gekkotron.athena.gateway.client.MqttEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapNotNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [GatewayApi] over the gateway's own Kotlin SDK (`athena-gateway-client`, gateway spec §3.3). The SDK owns
 * the wire format; this maps its events and errors to ours. Streams are opened without the SDK's
 * `reconnect`: [GatewayTransport] reconnects, with make-before-break.
 */
class AthenaGatewayApi internal constructor(
    private val client: AthenaGatewayClient,
    private val broker: Broker?,
) : GatewayApi {
    /** [http] is shared; publishes get a 10 s call timeout (the SDK clears it for streams). */
    constructor(spec: TransportSpec.Gateway, http: OkHttpClient = OkHttpClient()) : this(
        AthenaGatewayClient(
            requireHttps(spec.url),
            spec.key.toHex(),
            http.newBuilder().callTimeout(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS).build(),
        ),
        spec.broker(),
    )

    override fun stream(topics: Set<String>): Flow<GatewayEvent> =
        client.subscribe(*topics.sorted().toTypedArray(), qos = 1, broker = broker)
            .mapNotNull { event ->
                when (event) {
                    is MqttEvent.Connected -> GatewayEvent.Connected
                    is MqttEvent.Message -> GatewayEvent.Message(MqttMessage(event.topic, event.payload.toPayloadBytes(), event.retain))
                    else -> null // Reconnecting only comes with `reconnect`; newer event types are ignored
                }
            }
            .catch { throw it.toStreamFailure() }

    override suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean) {
        if (!isValidPublishTopic(topic)) throw PublishFailed("invalid publish topic '$topic'")
        try {
            client.publish(topic, payload.decodeToString(), qos, retain, broker)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GatewayException) {
            throw PublishFailed(e.publishText())
        }
    }

    private companion object {
        const val PUBLISH_TIMEOUT_SECONDS = 10L

        fun requireHttps(url: String): String = url.also { require(it.startsWith("https://")) { "the gateway URL must use https" } }
    }
}

/** A refusal only the user can fix, or an IOException that [GatewayTransport] retries with backoff. */
internal fun Throwable.toStreamFailure(): Throwable = when (this) {
    is CancellationException -> this
    is GatewayException.Unauthorized -> GatewayRefused(Refusal.KeyRejected)
    is GatewayException.Rejected -> GatewayRefused(if (reason == EXPIRED) Refusal.ClockSkew else Refusal.Forbidden(reason))
    is GatewayException.BadRequest -> GatewayRefused(Refusal.Forbidden("bad request"))
    is GatewayException.StreamError ->
        if (MQTT_REFUSED.containsMatchIn(reason)) GatewayRefused(Refusal.BrokerRejected) else IOException(reason, this)
    // Transport, GatewayError (including an undecryptable frame), anything else: worth another try.
    else -> IOException(message ?: javaClass.simpleName, this)
}

/** Short, secret-free text for "Not confirmed — Retry"; the known cases reuse the offline reasons. */
internal fun GatewayException.publishText(): String = when (this) {
    is GatewayException.Unauthorized -> LinkState.Offline.KEY_REJECTED
    is GatewayException.Rejected -> if (reason == EXPIRED) LinkState.Offline.CLOCK_SKEW else reason
    is GatewayException.GatewayError -> reason
    is GatewayException.Upstream -> errorText(body) ?: "broker error $status"
    else -> message ?: javaClass.simpleName
}

internal fun TransportSpec.Gateway.broker(): Broker? =
    if (brokerHost == null && username == null) null else Broker(host = brokerHost, username = username, password = password)

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private const val EXPIRED = "Request expired"

/** CONNACK 4 (bad username or password) and 5 (not authorised), as the gateway's paho callback words them. */
private val MQTT_REFUSED = Regex("""Connection failed with code [45]\b""")
