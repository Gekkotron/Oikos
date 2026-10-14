package com.gekkotron.oikos.feature.security.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.history.HistoryClient
import com.gekkotron.oikos.history.HistoryRef
import com.gekkotron.oikos.history.HistoryRefs
import com.gekkotron.oikos.history.HistoryResult
import com.gekkotron.oikos.history.LastChange
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.Placement
import com.gekkotron.oikos.model.SensorGroup
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.transport.LinkState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SecurityViewModel internal constructor(
    private val source: EntitySource,
    dispatcher: CoroutineDispatcher,
    private val history: HistoryClient = HistoryClient.None,
    refresh: Duration = 10.minutes,
) : ViewModel() {
    @Inject
    constructor(source: EntitySource, history: HistoryClient) : this(source, Dispatchers.Default, history)

    /** The change ref of every sensor the tab lists (same filter as [SecurityUiBuilder]). */
    private val refs: Flow<Map<String, HistoryRef>> = source.entities
        .map { all ->
            all.values
                .filter { it.partOf == null && it.kind == Kind.BINARY_SENSOR && Placement.onSecurity(it.kind, it.deviceClass, it.ui) && SensorGroup.of(it.deviceClass) != null }
                .mapNotNull { e -> HistoryRefs.changeRef(e)?.let { e.uniqueId to it } }
                .toMap()
        }
        .distinctUntilChanged()

    private val connected: Flow<Boolean> = source.link.map { it is LinkState.Connected }.distinctUntilChanged()

    /**
     * Last change by entity id (last-change spec §4.4): asked when the ref set changes, when the link comes back, and
     * every [refresh]; a failed answer emits nothing, so the previous times stay.
     */
    private val changes: Flow<Map<String, LastChange>> =
        combine(refs, connected, history.available, ::Triple)
            .flatMapLatest { (byId, isConnected, available) ->
                if (byId.isEmpty() || !isConnected || !available) {
                    flowOf()
                } else {
                    flow {
                        while (true) {
                            val r = history.lastChanges(byId.values.toList())
                            if (r is HistoryResult.Ok) emit(byId.mapNotNull { (id, ref) -> r.value[ref]?.let { id to it } }.toMap())
                            delay(refresh)
                        }
                    }
                }
            }
            // The tab doesn't wait on the first answer.
            .onStart { emit(emptyMap()) }

    // Like EnergyViewModel: one rebuild per collection turn, built off Main.
    val state: StateFlow<SecurityUi> = combine(source.entities, source.states(), source.areas, source.link, changes, SecurityUiBuilder::build)
        .conflate()
        .flowOn(dispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SecurityUi())

    private val _events = Channel<SecurityEvent>(Channel.BUFFERED)
    val events: Flow<SecurityEvent> = _events.receiveAsFlow()

    /** Home's view model reports its own failures; this one only those of commands sent from Security. */
    private val sentHere = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            source.commandFailures.collect { if (it.uniqueId in sentHere) _events.send(SecurityEvent.NotConfirmed(it.uniqueId, it.command)) }
        }
    }

    fun send(id: String, command: Command) {
        viewModelScope.launch {
            // A cached panel or action has no live link yet: sending would be dropped, so say so (like Home).
            val ui = state.value
            if (ui.panel(id)?.stale == true || ui.action(id)?.stale == true) return@launch _events.send(SecurityEvent.WaitingForConnection)
            sentHere += id
            val result = source.send(id, command)
            if (result is CommandResult.Rejected) _events.send(SecurityEvent.Rejected(result.reason))
        }
    }

    fun setSecurityHidden(id: String, hidden: Boolean) = save(id) { it.copy(securityHidden = hidden) }

    /** Security actions spec §3.4. */
    fun setAction(id: String, show: Boolean, ask: Boolean) = save(id) { it.copy(securityAction = show, securityAsk = ask) }

    private fun save(id: String, change: (UiOverride) -> UiOverride) {
        val entity = source.entities.value[id] ?: return
        viewModelScope.launch {
            try {
                source.setOverride(id, change(entity.ui))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(SecurityEvent.SaveFailed)
            }
        }
    }
}
