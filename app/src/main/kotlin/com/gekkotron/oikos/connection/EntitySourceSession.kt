package com.gekkotron.oikos.connection

import com.gekkotron.oikos.model.DiagnosticSink
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.source.MqttDiscoverySource
import com.gekkotron.oikos.transport.MqttTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Owns the discovery source for the current prefix (spec §2). The prefix is a constructor argument
 * of [MqttDiscoverySource], so a new prefix means a new source, and the old one's scope is cancelled.
 */
class EntitySourceSession(
    private val transport: MqttTransport,
    private val parent: CoroutineScope,
    private val diagnostics: DiagnosticSink = DiagnosticSink.None,
    private val build: (MqttTransport, CoroutineScope, String, DiagnosticSink) -> EntitySource =
        { transport, scope, prefix, diagnostics -> defaultBuild(transport, scope, prefix, diagnostics) },
) {
    private var prefix: String? = null
    private var scope: CoroutineScope? = null
    private var source: EntitySource? = null

    @Synchronized
    fun current(prefix: String): EntitySource {
        source?.let { if (prefix == this.prefix) return it }
        scope?.cancel()
        val child = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        return build(transport, child, prefix, diagnostics).also {
            scope = child
            source = it
            this.prefix = prefix
        }
    }

    companion object {
        fun defaultBuild(transport: MqttTransport, scope: CoroutineScope, prefix: String, diagnostics: DiagnosticSink): EntitySource =
            MqttDiscoverySource(transport, scope, prefix = prefix, diagnostics = diagnostics)
    }
}
