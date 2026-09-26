package com.gekkotron.oikos.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One `/mqtt/subscribe` stream for a fixed topic set, shaped as a [ManagedTransport] so [Handover] can
 * switch between streams. [GatewayTransport] owns reconnecting and publishing; this only reads.
 */
internal class GatewayStream(
    private val api: GatewayApi,
    val topics: Set<String>,
    private val scope: CoroutineScope,
) : ManagedTransport {
    sealed interface End {
        /** EOF, a disconnect, a retryable error (an IOException from [GatewayApi]), or close(): worth retrying. */
        data object Dropped : End

        /** Needs the user; [reason] is a [LinkState.Offline] constant. */
        data class Refused(val reason: String) : End
    }

    private val _link = MutableStateFlow<LinkState>(LinkState.Connecting)
    override val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _messages = MutableSharedFlow<MqttMessage>(extraBufferCapacity = 1024)
    private val _end = CompletableDeferred<End>()
    val end: Deferred<End> get() = _end
    private var job: Job? = null

    override fun messages(): SharedFlow<MqttMessage> = _messages.asSharedFlow()

    override fun start() {
        check(job == null) { "already started" }
        job = scope.launch {
            val outcome = try {
                read()
                End.Dropped
            } catch (e: GatewayRefused) {
                End.Refused(e.refusal.offlineReason)
            } catch (e: CancellationException) {
                // Only our own cancellation (close()) is rethrown. A CancellationException the SDK flow ended
                // with while we are still active is a dropped stream: rethrowing it would leave `end` forever
                // incomplete and GatewayTransport waiting on it.
                if (currentCoroutineContext().isActive) End.Dropped else throw e
            } catch (e: Exception) {
                End.Dropped
            }
            _link.value = LinkState.Offline(if (outcome is End.Refused) outcome.reason else LinkState.Offline.CLOSED)
            _end.complete(outcome)
        }
    }

    private suspend fun read() {
        api.stream(topics).collect { event ->
            when (event) {
                GatewayEvent.Connected -> _link.value = LinkState.Connected(TransportMode.GATEWAY)
                is GatewayEvent.Message -> _messages.emit(event.message)
            }
        }
    }

    override suspend fun close() {
        job?.cancelAndJoin()
        if (_end.complete(End.Dropped)) _link.value = LinkState.Offline(LinkState.Offline.CLOSED)
    }

    /** A stream's topics are fixed; [GatewayTransport] opens a new stream for a new set. */
    override suspend fun setSubscriptions(filters: Set<String>) = Unit

    override suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean): Unit =
        throw PublishFailed("streams do not publish")
}
