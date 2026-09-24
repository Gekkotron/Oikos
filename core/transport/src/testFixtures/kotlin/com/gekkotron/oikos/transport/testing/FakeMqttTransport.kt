package com.gekkotron.oikos.transport.testing

import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.MqttMessage
import com.gekkotron.oikos.transport.MqttTransport
import com.gekkotron.oikos.transport.PublishFailed
import com.gekkotron.oikos.transport.TransportMode
import com.gekkotron.oikos.transport.isValidPublishTopic
import com.gekkotron.oikos.transport.topicMatches
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory broker + transport for tests. Not thread-safe; use from a single test dispatcher. */
class FakeMqttTransport : MqttTransport {
    private val _link = MutableStateFlow<LinkState>(LinkState.Connected(TransportMode.DIRECT))
    override val link: StateFlow<LinkState> = _link

    private val _messages = MutableSharedFlow<MqttMessage>(extraBufferCapacity = 4096)
    private val retainedStore = linkedMapOf<String, ByteArray>()

    var subscriptions: Set<String> = emptySet()
        private set
    val subscriptionHistory = mutableListOf<Set<String>>()
    val published = mutableListOf<MqttMessage>()
    var failPublishes = false

    override fun messages(): SharedFlow<MqttMessage> = _messages

    override suspend fun setSubscriptions(filters: Set<String>) {
        val added = filters - subscriptions
        subscriptions = filters
        subscriptionHistory += filters
        for ((topic, payload) in retainedStore.toList()) {
            if (added.any { topicMatches(it, topic) }) _messages.emit(MqttMessage(topic, payload, retained = true))
        }
    }

    override suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean) {
        if (failPublishes) throw PublishFailed("fake publish failure")
        require(isValidPublishTopic(topic)) { "invalid publish topic '$topic'" }
        published += MqttMessage(topic, payload, retain)
        brokerReceive(topic, payload, retain)
    }

    /** Simulates another client publishing to the broker. */
    suspend fun deliver(topic: String, payload: String, retain: Boolean = false) =
        brokerReceive(topic, payload.encodeToByteArray(), retain)

    fun setLink(state: LinkState) {
        _link.value = state
    }

    /** Seeds a retained message without delivering it, as if it was published before anyone connected. */
    fun retain(topic: String, payload: String) {
        retainedStore[topic] = payload.encodeToByteArray()
    }

    fun publishedStrings(): List<Pair<String, String>> = published.map { it.topic to it.payloadString() }

    private suspend fun brokerReceive(topic: String, payload: ByteArray, retain: Boolean) {
        if (retain) {
            if (payload.isEmpty()) retainedStore.remove(topic) else retainedStore[topic] = payload
        }
        if (subscriptions.any { topicMatches(it, topic) }) _messages.emit(MqttMessage(topic, payload, retained = false))
    }
}
