package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.EntityCache
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.data.RoutePreference
import com.gekkotron.oikos.data.effectiveHistoryUrl
import com.gekkotron.oikos.feature.widget.CatalogResult
import com.gekkotron.oikos.feature.widget.Failure
import com.gekkotron.oikos.feature.widget.RefreshResult
import com.gekkotron.oikos.feature.widget.SendResult
import com.gekkotron.oikos.feature.widget.WidgetEntities
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.source.MqttDiscoverySource
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.ManagedTransport
import com.gekkotron.oikos.transport.MqttTransport
import com.gekkotron.oikos.transport.TransportSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The widget's door to the house (widgets spec §3.3): the live source when the app is connected, otherwise a short
 * session (its own transport and a fresh discovery source restored from the cache) that is always closed before returning.
 */
class HeadlessEntities(
    /** The first non-Loading profile state. */
    private val profile: suspend () -> ProfileState,
    private val route: () -> RoutePreference,
    private val installId: suspend () -> String,
    /** The foreground source when the app's transport is connected, else null. */
    private val live: () -> EntitySource?,
    private val foreground: () -> Boolean,
    private val cache: EntityCache,
    private val transports: (TransportSpec, CoroutineScope) -> ManagedTransport,
    private val sources: (MqttTransport, CoroutineScope, String) -> MqttDiscoverySource =
        { transport, scope, prefix -> MqttDiscoverySource(transport, scope, prefix = prefix) },
    private val connectBudget: Duration = 10.seconds,
    private val waitBudget: Duration = 8.seconds,
    /** Settling needs live traffic then a 2 s quiet window that each new replayed topic restarts: about 4 s on a small house. */
    private val settleBudget: Duration = 6.seconds,
    private val confirmBudget: Duration = 7.seconds,
    private val context: CoroutineContext = Dispatchers.IO,
) : WidgetEntities {
    private val mutex = Mutex()

    private sealed interface Session<out T> {
        data class Ok<T>(val value: T) : Session<T>

        data class Failed(val reason: Failure) : Session<Nothing>
    }

    override suspend fun refresh(ids: Set<String>): RefreshResult {
        live()?.let { source ->
            // Just connected (or still restoring the cache), the live source may not list the whole house yet.
            val complete = (source as? MqttDiscoverySource)?.hasSettled?.value ?: true
            return RefreshResult.Ok(source.entities.value, source.states().first(), complete, historyEnabled(readyProfile()))
        }
        val session = session(save = true) { source, history ->
            awaitLiveOrSettled(source, ids)
            withTimeoutOrNull(settleBudget) { source.hasSettled.first { it } }
            RefreshResult.Ok(source.entities.value, source.states().first(), complete = source.hasSettled.value, historyEnabled = history)
        }
        return when (session) {
            is Session.Ok -> session.value
            is Session.Failed -> RefreshResult.Failed(session.reason)
        }
    }

    override suspend fun send(id: String, command: Command): SendResult {
        live()?.let { return sendOn(it, id, command, historyEnabled(readyProfile())) }
        val session = session(save = true) { source, history ->
            if (withTimeoutOrNull(waitBudget) { source.entities.first { id in it } } == null) return@session SendResult.NotConfirmed
            // Wait for the state topic's retained replay first: arriving after the send, it would "confirm" the old value.
            // A button has no state topic, so there is nothing to wait for.
            if (source.entities.value[id]?.kind != Kind.BUTTON) awaitLiveOrSettled(source, setOf(id))
            sendOn(source, id, command, history)
        }
        return when (session) {
            is Session.Ok -> session.value
            is Session.Failed -> SendResult.Failed(session.reason)
        }
    }

    override suspend fun catalog(): CatalogResult {
        live()?.let { return CatalogResult.Ok(it.entities.value.values.toList(), it.areas.value) }
        // Offline is fine here: the cache restored into the source still lists the house (spec §2.1).
        val session = session(save = false, allowOffline = true) { source, _ ->
            withTimeoutOrNull(waitBudget) { source.hasSettled.first { it } }
            CatalogResult.Ok(source.entities.value.values.toList(), source.areas.value)
        }
        return when (session) {
            is Session.Ok -> session.value
            is Session.Failed -> CatalogResult.Failed(session.reason)
        }
    }

    /**
     * Until every id has a live state or discovery settled, whichever comes first, within [waitBudget]. A settled source
     * has seen the broker's retained replay end, so a state topic the broker does not retain (Zigbee2MQTT's default)
     * costs about one quiet window instead of the whole budget, and no late replay can "confirm" a command.
     */
    private suspend fun awaitLiveOrSettled(source: MqttDiscoverySource, ids: Set<String>) {
        withTimeoutOrNull(waitBudget) {
            combine(source.states(), source.hasSettled) { states, settled ->
                settled || ids.all { id -> states[id]?.let { !it.stale && it.lastUpdate != null } == true }
            }.first { it }
        }
    }

    /** Sends, then waits for the tracker to confirm (a state echo) or to report a failure. */
    private suspend fun sendOn(source: EntitySource, id: String, command: Command, history: Boolean): SendResult = coroutineScope {
        val failed = MutableStateFlow(false)
        // Subscribed before sending: commandFailures does not replay.
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            source.commandFailures.first { it.uniqueId == id }
            failed.value = true
        }
        try {
            val sentAt = source.states().first()[id]?.lastUpdate
            if (source.send(id, command) is CommandResult.Rejected) return@coroutineScope SendResult.NotConfirmed
            // Nothing pending right after an accepted send: no prediction to confirm (a button press) or an optimistic
            // entity confirmed on the spot. The publish went out, so that is the confirmation.
            val after = source.states().first()[id]
            val entityNow = source.entities.value[id]
            if (after?.pending != true && entityNow != null) {
                return@coroutineScope SendResult.Confirmed(entityNow, after ?: EntityState(id, StateValue.Unknown), history)
            }
            val done = withTimeoutOrNull(confirmBudget) {
                combine(source.states(), failed) { states, f -> f to states[id] }.first { (f, state) -> f || state?.pending == false }
            }
            val state = done?.second
            val entity = source.entities.value[id]
            // A timeout reverts the store (pending=false) before the failure is emitted; a revert keeps lastUpdate, a confirmation moves it.
            val confirmed = done != null && !done.first && state != null && state.lastUpdate != sentAt
            if (!confirmed || entity == null) SendResult.NotConfirmed
            else SendResult.Confirmed(entity, state!!, history)
        } finally {
            watcher.cancel()
        }
    }

    private suspend fun <T> session(save: Boolean, allowOffline: Boolean = false, block: suspend (MqttDiscoverySource, Boolean) -> T): Session<T> =
        mutex.withLock {
            withContext(context) {
                val state = profile()
                val ready = state as? ProfileState.Ready ?: return@withContext Session.Failed(Failure.NOT_CONFIGURED)
                val spec = state.toSpec(clientIdFor(installId()) + CLIENT_SUFFIX, route()) ?: return@withContext Session.Failed(Failure.NOT_CONFIGURED)
                val scope = CoroutineScope(SupervisorJob() + context)
                val transport = transports(spec, scope)
                try {
                    // Built before start(): the source subscribes to the transport's messages first (ManagedTransport contract).
                    val source = sources(transport, scope, ready.profile.prefix)
                    val key = (ready.profile as? ConnectionProfile.Direct)?.cacheKey()
                    if (key != null) source.restore(cache.load(key))
                    transport.start()
                    val link = withTimeoutOrNull(connectBudget) {
                        transport.link.first { it is LinkState.Connected || (it is LinkState.Offline && it.reason in AUTH_REASONS) }
                    }
                    val failure = when (link) {
                        is LinkState.Connected -> null
                        is LinkState.Offline -> Failure.AUTH_FAILED
                        else -> Failure.OFFLINE
                    }
                    if (failure != null && !(allowOffline && failure == Failure.OFFLINE)) return@withContext Session.Failed(failure)
                    val value = block(source, historyEnabled(ready.profile))
                    // Only a settled journal is complete; a partial one would shrink the house the app shows at cold start.
                    if (save && key != null && failure == null && source.hasSettled.value && !foreground()) {
                        val journal = source.journal()
                        if (journal.isNotEmpty()) cache.save(key, journal)
                    }
                    Session.Ok(value)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Session.Failed(Failure.OFFLINE)
                } finally {
                    withContext(NonCancellable) {
                        try {
                            transport.close()
                        } catch (e: Exception) {
                            // close() is contractually quiet; a failure here must not hide the session's result.
                        }
                        scope.cancel()
                    }
                }
            }
        }

    private suspend fun readyProfile(): ConnectionProfile? = (profile() as? ProfileState.Ready)?.profile

    private fun historyEnabled(profile: ConnectionProfile?): Boolean = (profile as? ConnectionProfile.Direct)?.effectiveHistoryUrl() != null

    companion object {
        const val CLIENT_SUFFIX = "-widget"
        private val AUTH_REASONS = setOf(LinkState.Offline.REJECTED, LinkState.Offline.KEY_REJECTED)
    }
}
