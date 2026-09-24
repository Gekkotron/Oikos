package com.gekkotron.oikos.transport.testing

import com.gekkotron.oikos.transport.ManagedTransport
import com.gekkotron.oikos.transport.MqttTransport
import com.gekkotron.oikos.transport.TransportSpec

/** A [FakeMqttTransport] with a recorded lifetime, created by an `ActiveTransport` factory in tests. */
class FakeManagedTransport(
    val spec: TransportSpec,
    val fake: FakeMqttTransport = FakeMqttTransport(),
) : ManagedTransport, MqttTransport by fake {
    var started = false
        private set
    var closed = false
        private set

    override fun start() {
        started = true
    }

    override suspend fun close() {
        closed = true
    }
}
