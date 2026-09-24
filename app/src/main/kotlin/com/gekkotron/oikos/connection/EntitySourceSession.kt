package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.DEFAULT_PREFIX
import com.gekkotron.oikos.model.DiagnosticSink
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.source.MqttDiscoverySource
import com.gekkotron.oikos.transport.MqttTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Owns the discovery source for the current profile (spec §2). A source remembers every entity,
 * override and state it has seen, so a different profile (even one with the same prefix, such as the
 * demo and a default direct broker) or a [reset] means a new source, and the old one's scope is cancelled.
 */
class EntitySourceSession(
    private val transport: MqttTransport,
    private val parent: CoroutineScope,
    private val diagnostics: DiagnosticSink = DiagnosticSink.None,
    private val build: (MqttTransport, CoroutineScope, String, DiagnosticSink) -> EntitySource =
        { transport, scope, prefix, diagnostics -> defaultBuild(transport, scope, prefix, diagnostics) },
) {
    private var profile: ConnectionProfile? = null
    private var scope: CoroutineScope? = null
    private var source: EntitySource? = null

    /** The source for [profile]; `null` (no saved profile) gets a source for the default prefix. */
    @Synchronized
    fun current(profile: ConnectionProfile?): EntitySource {
        source?.let { if (profile == this.profile) return it }
        drop()
        val child = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        return build(transport, child, profile?.prefix ?: DEFAULT_PREFIX, diagnostics).also {
            scope = child
            source = it
            this.profile = profile
        }
    }

    /**
     * "Set up again": drops the current source, so the next [current] builds a new one even for an
     * equal profile, and clears its subscriptions. Without that, the next transport would start with
     * the old filters and replay its retained messages before the new source exists, and the new
     * source's identical filters would then subscribe to nothing new.
     */
    suspend fun reset() {
        drop()
        transport.setSubscriptions(emptySet())
    }

    @Synchronized
    private fun drop() {
        scope?.cancel()
        scope = null
        source = null
        profile = null
    }

    companion object {
        fun defaultBuild(transport: MqttTransport, scope: CoroutineScope, prefix: String, diagnostics: DiagnosticSink): EntitySource =
            MqttDiscoverySource(transport, scope, prefix = prefix, diagnostics = diagnostics)
    }
}
