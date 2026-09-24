package com.gekkotron.oikos.transport

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class TransportMode { DIRECT, GATEWAY }

sealed interface LinkState {
    data object Connecting : LinkState
    data class Connected(val mode: TransportMode) : LinkState
    data class Reconnecting(val attempt: Int) : LinkState
    data class Offline(val reason: String) : LinkState
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
