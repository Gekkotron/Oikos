package com.gekkotron.oikos.transport.testing

import com.gekkotron.oikos.transport.ManagedTransport
import com.gekkotron.oikos.transport.MqttTransport
import com.gekkotron.oikos.transport.TransportSpec

/**
 * A [FakeMqttTransport] with a recorded lifetime, created by an `ActiveTransport` factory in tests.
 * [failStart]/[failClose] make [start]/[close] throw (after recording the call) to exercise
 * `ActiveTransport`'s handling of a misbehaving transport.
 */
class FakeManagedTransport(
    val spec: TransportSpec,
    val fake: FakeMqttTransport = FakeMqttTransport(),
    private val failStart: Boolean = false,
    private val failClose: Boolean = false,
) : ManagedTransport, MqttTransport by fake {
    var started = false
        private set
    var closed = false
        private set

    override fun start() {
        started = true
        if (failStart) throw IllegalStateException("fake start failure")
    }

    override suspend fun close() {
        closed = true
        if (failClose) throw IllegalStateException("fake close failure")
    }
}
