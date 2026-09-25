package com.gekkotron.oikos.transport

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class TransportMode { DIRECT, GATEWAY, DEMO }

sealed interface LinkState {
    data object Connecting : LinkState
    data class Connected(val mode: TransportMode) : LinkState
    data class Reconnecting(val attempt: Int) : LinkState
    /** [reason] is one of the constants below; the UI maps each to its own text (never shows it raw). */
    data class Offline(val reason: String) : LinkState {
        companion object {
            const val NOT_CONFIGURED = "not configured"
            const val PAUSED = "paused"
            const val REJECTED = "broker rejected credentials"
            const val CLOSED = "closed"
            const val NOT_STARTED = "not started"
            const val FAILED = "failed to start"

            /** Gateway HTTP 401: no key on the gateway matched ours. */
            const val KEY_REJECTED = "gateway key not accepted"

            /** Gateway 403 "Request expired": the phone's clock is outside the gateway's window. */
            const val CLOCK_SKEW = "phone clock is off"

            /** Any other gateway 403 (port or destination not allowed for this key). */
            const val GATEWAY_REFUSED = "gateway refused"
        }
    }
}

class MqttMessage(val topic: String, val payload: ByteArray, val retained: Boolean) {
    fun payloadString(): String = payload.decodeToString()

    override fun equals(other: Any?): Boolean =
        other is MqttMessage && topic == other.topic && retained == other.retained && payload.contentEquals(other.payload)

    override fun hashCode(): Int = (topic.hashCode() * 31 + payload.contentHashCode()) * 31 + retained.hashCode()

    override fun toString(): String = "MqttMessage($topic, ${payload.size} bytes, retained=$retained)"
}

class PublishFailed(message: String) : Exception(message)

interface MqttTransport {
    val link: StateFlow<LinkState>

    fun messages(): SharedFlow<MqttMessage>

    /** Declarative: the transport diffs against the current set and (re)subscribes. */
    suspend fun setSubscriptions(filters: Set<String>)

    /** @throws PublishFailed when the message could not be delivered to the broker. */
    suspend fun publish(topic: String, payload: ByteArray, qos: Int = 1, retain: Boolean = false)
}
