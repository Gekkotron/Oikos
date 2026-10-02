package com.gekkotron.oikos.demo

import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.ManagedTransport
import com.gekkotron.oikos.transport.MqttMessage
import com.gekkotron.oikos.transport.PublishFailed
import com.gekkotron.oikos.transport.TransportMode
import com.gekkotron.oikos.transport.isValidPublishTopic
import com.gekkotron.oikos.transport.topicMatches
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * In-process broker for demo mode. It keeps retained messages, echoes commands back as state after
 * [echoDelay] (so the pending dot is visible), and can tick live sensors. Nothing leaves the device.
 */
class DemoBroker(
    private val scope: CoroutineScope,
    private val house: DemoHouse = DemoHouse(),
    private val echoDelay: Duration = 300.milliseconds,
) : ManagedTransport {
    override val link: StateFlow<LinkState> = MutableStateFlow(LinkState.Connected(TransportMode.DEMO)).asStateFlow()

    private val messages = MutableSharedFlow<MqttMessage>(extraBufferCapacity = 4096)
    private val mutex = Mutex()
    private val retained = linkedMapOf<String, ByteArray>()
    private var subscriptions: Set<String> = emptySet()
    private var ticking: Job? = null

    init {
        for (m in house.retainedMessages()) retained[m.topic] = m.payload.encodeToByteArray()
    }

    override fun messages(): SharedFlow<MqttMessage> = messages

    override suspend fun setSubscriptions(filters: Set<String>) {
        val replay = mutex.withLock {
            val added = filters - subscriptions
            subscriptions = filters
            retained.filterKeys { topic -> added.any { topicMatches(it, topic) } }.toList()
        }
        for ((topic, payload) in replay) messages.emit(MqttMessage(topic, payload, retained = true))
    }

    override suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean) {
        if (!isValidPublishTopic(topic)) throw PublishFailed("invalid publish topic '$topic'")
        receive(topic, payload, retain)
        val echo = house.onCommand(topic, payload.decodeToString()) ?: return
        scope.launch {
            delay(echoDelay)
            val latest = house.current(echo.topic) ?: echo
            receive(latest.topic, latest.payload.encodeToByteArray(), retain = true)
        }
    }

    override fun start() {
        if (ticking == null) ticking = startTicking()
    }

    override suspend fun close() {
        ticking?.cancel()
        ticking = null
    }

    /** Publishes live sensor updates every [interval] until cancelled. */
    fun startTicking(interval: Duration = 3.seconds): Job = scope.launch {
        while (true) {
            delay(interval)
            for (m in house.tick()) receive(m.topic, m.payload.encodeToByteArray(), retain = true)
        }
    }

    private suspend fun receive(topic: String, payload: ByteArray, retain: Boolean) {
        val deliver = mutex.withLock {
            if (retain) {
                if (payload.isEmpty()) retained.remove(topic) else retained[topic] = payload
            }
            subscriptions.any { topicMatches(it, topic) }
        }
        if (deliver) messages.emit(MqttMessage(topic, payload, retained = false))
    }
}
