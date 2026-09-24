package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.feature.onboarding.ConnectionTester
import com.gekkotron.oikos.feature.onboarding.TestFailure
import com.gekkotron.oikos.feature.onboarding.TestState
import com.gekkotron.oikos.model.Device
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.source.MqttDiscoverySource
import com.gekkotron.oikos.transport.DirectFailure
import com.gekkotron.oikos.transport.DirectMqttTransport
import com.gekkotron.oikos.transport.LinkState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Onboarding's connection test (spec §5.1). It uses a temporary transport and discovery source, so
 * the device count comes from the real parser. Cancelling the flow closes the connection.
 */
class DirectConnectionTester(
    private val clientId: suspend () -> String,
    private val connectTimeout: Duration = 5.seconds,
) : ConnectionTester {
    override fun test(draft: ConnectionProfile.Direct): Flow<TestState> = channelFlow {
        send(TestState.Connecting)
        val transport = DirectMqttTransport(draft.local.toSpec(clientId()), this)
        try {
            transport.start()
            // null = still trying; true = connected; a failure = definitive (no point waiting for the timeout).
            val outcome = withTimeoutOrNull(connectTimeout) {
                combine(transport.link, transport.failure) { link, failure ->
                    when {
                        link is LinkState.Connected -> true
                        failure == DirectFailure.Rejected || failure is DirectFailure.Tls -> failure
                        else -> null
                    }
                }.first { it != null }
            }
            if (outcome != true) {
                send(TestState.Failed((outcome as? DirectFailure ?: transport.failure.value).toTestFailure()))
                return@channelFlow
            }
            val source = MqttDiscoverySource(transport, this, prefix = draft.prefix)
            combine(source.devices, source.entities, ::deviceNames)
                .distinctUntilChanged()
                .collect { send(TestState.Connected(it)) }
        } finally {
            withContext(NonCancellable) { transport.close() }
        }
    }
}

/** Device names, plus entities that belong to no known device; sorted case-insensitively. */
internal fun deviceNames(devices: Map<String, Device>, entities: Map<String, Entity>): List<String> {
    val loose = entities.values.filter { it.deviceId == null || it.deviceId !in devices }.map { it.name }
    return (devices.values.map { it.name } + loose).sortedBy { it.lowercase() }
}

internal fun DirectFailure?.toTestFailure(): TestFailure = when (this) {
    null, DirectFailure.Unreachable -> TestFailure.Unreachable
    DirectFailure.Rejected -> TestFailure.Rejected
    is DirectFailure.Tls -> TestFailure.Tls
    is DirectFailure.Other -> TestFailure.Other(message)
}
