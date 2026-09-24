package com.gekkotron.oikos.transport

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedContext
import com.hivemq.client.mqtt.lifecycle.MqttDisconnectSource
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * MQTT 3.1.1 to the local broker over TCP, TLS or WebSocket (spec §3.2). Clean session; our own
 * subscription set is re-applied after every reconnect. Messages are never dropped.
 */
class DirectMqttTransport(
    private val spec: TransportSpec.Direct,
    private val scope: CoroutineScope,
    reconnectInitial: Duration = 1.seconds,
    reconnectMax: Duration = 30.seconds,
    private val publishTimeout: Duration = 10.seconds,
) : ManagedTransport {
    private val _link = MutableStateFlow<LinkState>(LinkState.Offline(LinkState.Offline.NOT_STARTED))
    override val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _failure = MutableStateFlow<DirectFailure?>(null)

    /** Why the last attempt failed; cleared on connect. Read by the onboarding connection test. */
    val failure: StateFlow<DirectFailure?> = _failure.asStateFlow()

    private val inbox = Channel<MqttMessage>(Channel.UNLIMITED)
    private val _messages = MutableSharedFlow<MqttMessage>(extraBufferCapacity = 1024)
    private val mutex = Mutex()
    private var subscriptions: Set<String> = emptySet()
    private val attempts = AtomicInteger()

    @Volatile
    private var closed = false
    private var pump: Job? = null

    private val client: Mqtt3AsyncClient = buildClient(reconnectInitial, reconnectMax)

    override fun messages(): SharedFlow<MqttMessage> = _messages.asSharedFlow()

    override fun start() {
        check(pump == null) { "already started" }
        // Wait for the first collector, so nothing is emitted into a SharedFlow nobody listens to.
        pump = scope.launch {
            _messages.subscriptionCount.first { it > 0 }
            for (message in inbox) _messages.emit(message)
        }
        _link.value = LinkState.Connecting
        client.connectWith().cleanSession(true).send() // the outcome arrives through the listeners
    }

    override suspend fun close() {
        closed = true
        try {
            client.disconnect().await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Not connected: nothing to disconnect; `closed` stops any pending reconnect.
        }
        pump?.cancel()
        inbox.close()
        _link.value = LinkState.Offline(LinkState.Offline.CLOSED)
    }

    override suspend fun setSubscriptions(filters: Set<String>) {
        val (added, removed) = mutex.withLock {
            val diff = (filters - subscriptions) to (subscriptions - filters)
            subscriptions = filters
            diff
        }
        if (!client.state.isConnected) return // onConnected applies the whole set
        added.forEach { subscribe(it) }
        removed.forEach { unsubscribe(it) }
    }

    override suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean) {
        if (!isValidPublishTopic(topic)) throw PublishFailed("invalid publish topic '$topic'")
        if (!client.state.isConnected) throw PublishFailed("not connected")
        val level = MqttQos.fromCode(qos) ?: throw PublishFailed("invalid QoS $qos")
        try {
            withTimeout(publishTimeout) {
                client.publishWith().topic(topic).payload(payload).qos(level).retain(retain).send().await()
            }
        } catch (e: TimeoutCancellationException) {
            throw PublishFailed("publish timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PublishFailed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun onConnected() {
        if (closed) {
            client.disconnect()
            return
        }
        // HiveMQ clears every incoming-publish flow -- including a global one registered via
        // `publishes()` -- on session end, because `resubscribeIfSessionExpired` is false (see
        // onDisconnected). So the global consumer must be re-registered on every reconnect, not
        // just once in start(), or messages after a reconnect are silently dropped.
        client.publishes(MqttGlobalPublishFilter.ALL) { p -> inbox.trySend(p.toMqttMessage()) }
        attempts.set(0)
        _failure.value = null
        _link.value = LinkState.Connected(TransportMode.DIRECT)
        scope.launch {
            // Clean session: the broker forgot our subscriptions, so re-apply all of them.
            val filters = mutex.withLock { subscriptions }
            filters.forEach { subscribe(it) }
        }
    }

    private fun onDisconnected(context: MqttClientDisconnectedContext) {
        val reconnector = context.reconnector
        reconnector.resubscribeIfSessionExpired(false) // onConnected re-applies our own set
        if (closed || context.source == MqttDisconnectSource.USER) {
            reconnector.reconnect(false)
            _link.value = LinkState.Offline(LinkState.Offline.CLOSED)
            return
        }
        val failure = classifyFailure(context.cause)
        _failure.value = failure
        if (failure == DirectFailure.Rejected) {
            reconnector.reconnect(false)
            _link.value = LinkState.Offline(LinkState.Offline.REJECTED)
        } else {
            _link.value = LinkState.Reconnecting(attempts.incrementAndGet())
        }
    }

    private suspend fun subscribe(filter: String) = ignoreFailure {
        client.subscribeWith().topicFilter(filter).qos(MqttQos.AT_LEAST_ONCE).send().await()
    }

    private suspend fun unsubscribe(filter: String) = ignoreFailure {
        client.unsubscribeWith().topicFilter(filter).send().await()
    }

    /** A failed (un)subscribe means the connection dropped; onConnected re-applies the set. */
    private suspend fun ignoreFailure(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun buildClient(initial: Duration, max: Duration): Mqtt3AsyncClient {
        var builder = MqttClient.builder()
            .useMqttVersion3()
            .identifier(spec.clientId)
            .serverHost(spec.host)
            .serverPort(spec.port)
            .automaticReconnect()
            .initialDelay(initial.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .maxDelay(max.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .applyAutomaticReconnect()
            .addConnectedListener { onConnected() }
            .addDisconnectedListener { onDisconnected(it) }
        if (spec.tls) builder = builder.sslWithDefaultConfig()
        if (spec.ws) builder = builder.webSocketConfig().serverPath(WS_PATH).applyWebSocketConfig()
        if (spec.username != null) {
            builder = builder.simpleAuth()
                .username(spec.username)
                .password(spec.password.orEmpty().encodeToByteArray())
                .applySimpleAuth()
        }
        return builder.buildAsync()
    }

    private companion object {
        const val WS_PATH = "mqtt"
    }
}

/** Maps a HiveMQ publish to our own message type. Extracted so the retain-flag mapping is unit-testable without a broker. */
internal fun Mqtt3Publish.toMqttMessage(): MqttMessage = MqttMessage(topic.toString(), payloadAsBytes, isRetain)
