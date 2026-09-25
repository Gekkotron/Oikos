package com.gekkotron.oikos.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The gateway as [GatewayTransport] needs it (gateway spec §3.3). [AthenaGatewayApi] implements it over the
 * gateway's own SDK; tests use `FakeGatewayApi`.
 */
interface GatewayApi {
    /**
     * One `/mqtt/subscribe` stream, never reconnected here. Completes when the gateway ends it; throws
     * [GatewayRefused] when only the user can fix it, and an `IOException` for anything worth retrying.
     */
    fun stream(topics: Set<String>): Flow<GatewayEvent>

    /** @throws PublishFailed */
    suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean)
}

sealed interface GatewayEvent {
    /** The gateway connected to the broker and subscribed. */
    data object Connected : GatewayEvent

    data class Message(val message: MqttMessage) : GatewayEvent
}

/** Why the gateway refused us. None of these retry: they need the user (gateway spec §6). */
sealed interface Refusal {
    val offlineReason: String

    data object KeyRejected : Refusal {
        override val offlineReason: String get() = LinkState.Offline.KEY_REJECTED
    }

    data object ClockSkew : Refusal {
        override val offlineReason: String get() = LinkState.Offline.CLOCK_SKEW
    }

    /** The broker refused the gateway's login with our credentials (CONNACK 4 or 5). */
    data object BrokerRejected : Refusal {
        override val offlineReason: String get() = LinkState.Offline.REJECTED
    }

    data class Forbidden(val reason: String) : Refusal {
        override val offlineReason: String get() = LinkState.Offline.GATEWAY_REFUSED
    }
}

class GatewayRefused(val refusal: Refusal) : Exception("gateway refused: $refusal")

/**
 * The gateway JSON-decodes every payload (gateway spec §2): a string comes back as its raw text (so `""`
 * is still an empty payload), anything else as compact JSON that templates can still parse.
 */
internal fun JsonElement?.toPayloadBytes(): ByteArray = when {
    this == null -> ByteArray(0)
    this is JsonPrimitive && isString -> content.encodeToByteArray()
    else -> toString().encodeToByteArray()
}

/** The gateway's `body` is an object, or a JSON string holding one (gateway spec §2). */
internal fun errorText(body: JsonElement?): String? {
    val obj = when (body) {
        is JsonObject -> body
        is JsonPrimitive -> if (body.isString) {
            try {
                Json.parseToJsonElement(body.content) as? JsonObject
            } catch (e: SerializationException) {
                null
            }
        } else {
            null
        }
        else -> null
    }
    return listOf("error", "message").firstNotNullOfOrNull { key ->
        (obj?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
