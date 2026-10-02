package com.gekkotron.oikos.transport

/** A transport with an explicit lifetime, owned by [ActiveTransport]. */
interface ManagedTransport : MqttTransport {
    /** Starts connecting. Callers subscribe to [messages] first. */
    fun start()

    /** Disconnects for good. Safe to call when not connected, and idempotent: a second call does nothing. */
    suspend fun close()
}
