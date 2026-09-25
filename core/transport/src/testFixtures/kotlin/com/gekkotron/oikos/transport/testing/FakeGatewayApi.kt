package com.gekkotron.oikos.transport.testing

import com.gekkotron.oikos.transport.GatewayApi
import com.gekkotron.oikos.transport.GatewayEvent
import com.gekkotron.oikos.transport.MqttMessage
import com.gekkotron.oikos.transport.PublishFailed
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.CopyOnWriteArrayList

/** A scripted gateway: each [stream] call opens a [Stream] the test drives. */
class FakeGatewayApi : GatewayApi {
    class Stream(val topics: Set<String>) {
        internal val events = Channel<GatewayEvent>(Channel.UNLIMITED)

        /** True while the transport is still reading this stream. */
        @Volatile
        var active = true
            internal set

        fun send(event: GatewayEvent) {
            events.trySend(event)
        }

        fun connect() = send(GatewayEvent.Connected)

        fun message(topic: String, payload: String, retained: Boolean = false) =
            send(GatewayEvent.Message(MqttMessage(topic, payload.encodeToByteArray(), retained)))

        /** Ends the stream normally, or with [cause] (an IOException, a GatewayRefused…). */
        fun end(cause: Throwable? = null) {
            events.close(cause)
        }
    }

    val streams: MutableList<Stream> = CopyOnWriteArrayList()
    val published: MutableList<MqttMessage> = CopyOnWriteArrayList()
    var failPublish: String? = null

    /** Runs as each stream opens, before any event is read, for example `{ it.connect() }`. */
    var onOpen: (Stream) -> Unit = {}

    override fun stream(topics: Set<String>): Flow<GatewayEvent> = flow {
        val s = Stream(topics)
        streams += s
        onOpen(s)
        try {
            for (e in s.events) emit(e)
        } finally {
            s.active = false
        }
    }

    override suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean) {
        failPublish?.let { throw PublishFailed(it) }
        published += MqttMessage(topic, payload, retain)
    }
}
