package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.DEFAULT_PREFIX
import com.gekkotron.oikos.data.EntityCache
import com.gekkotron.oikos.model.Diagnostic
import com.gekkotron.oikos.model.DiagnosticSink
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.source.MqttDiscoverySource
import com.gekkotron.oikos.transport.MqttTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Owns the discovery source for the current profile (spec §2). A source remembers every entity,
 * override and state it has seen, so a different profile (even one with the same prefix, such as the
 * demo and a default direct broker) or a [reset] means a new source, and the old one's scope is cancelled.
 *
 * With a [cache], a direct source starts from the house it last showed and keeps the cache up to date;
 * the demo, no profile and [reset] forget it (entity cache spec §3.5).
 */
class EntitySourceSession(
    private val transport: MqttTransport,
    private val parent: CoroutineScope,
    private val diagnostics: DiagnosticSink = DiagnosticSink.None,
    private val cache: EntityCache? = null,
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
        val built = build(transport, child, profile?.prefix ?: DEFAULT_PREFIX, diagnostics)
        if (cache != null) {
            when (profile) {
                is ConnectionProfile.Direct -> attach(built, cache, profile.cacheKey(), child)
                // The demo and "no profile" never show a real house: forget any cached one.
                else -> parent.launch { cache.clear() }
            }
        }
        return built.also {
            scope = child
            source = it
            this.profile = profile
        }
    }

    @OptIn(FlowPreview::class)
    private fun attach(source: EntitySource, cache: EntityCache, key: String, scope: CoroutineScope) {
        scope.launch {
            source.restore(cache.load(key))
            val journaled = source as? MqttDiscoverySource ?: return@launch
            journaled.journalVersion.debounce(SAVE_DEBOUNCE).collect {
                val messages = journaled.journal()
                if (messages.isEmpty()) return@collect
                try {
                    cache.save(key, messages)
                } catch (e: IOException) {
                    diagnostics.report(Diagnostic("cache", "save failed: $e"))
                }
            }
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
        cache?.clear()
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
        val SAVE_DEBOUNCE: Duration = 2.seconds

        fun defaultBuild(transport: MqttTransport, scope: CoroutineScope, prefix: String, diagnostics: DiagnosticSink): EntitySource =
            MqttDiscoverySource(transport, scope, prefix = prefix, diagnostics = diagnostics)
    }
}
