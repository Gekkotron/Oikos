package com.gekkotron.oikos.connection

import com.gekkotron.oikos.transport.MqttTransport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * `setSubscriptions` is declarative: the last caller's set is the set. The discovery source owns it, so features that
 * need an extra topic (the alert history) register it here and it is merged into whatever the source asks for.
 */
class SharedSubscriptions(private val delegate: MqttTransport) : MqttTransport by delegate {
    private val mutex = Mutex()
    private var owned = emptySet<String>()
    private var extra = emptySet<String>()

    override suspend fun setSubscriptions(filters: Set<String>) = mutex.withLock {
        owned = filters
        delegate.setSubscriptions(owned + extra)
    }

    suspend fun setExtra(filters: Set<String>) = mutex.withLock {
        extra = filters
        delegate.setSubscriptions(owned + extra)
    }
}
