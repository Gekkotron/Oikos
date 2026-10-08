package com.gekkotron.oikos.feature.home.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.history.HistoryClient
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.source.EntitySource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val source: EntitySource,
    private val savedState: SavedStateHandle,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val labels: LabelText = LabelText { null },
    private val history: HistoryClient = HistoryClient.None,
) : ViewModel() {
    // conflate() collapses a burst of state messages to one rebuild per collection turn instead of one
    // per message, and flowOn moves the combine (and HomeUiBuilder.build) off Main; the final value is
    // never dropped.
    val state: StateFlow<HomeUiState> = combine(
        source.entities,
        source.states(),
        source.areas,
        combine(savedState.getStateFlow(KEY_FILTER, RoomFilter.All.key), savedState.getStateFlow(KEY_QUERY, ""), ::Pair),
        combine(source.link, history.available, ::Pair),
    ) { entities, states, areas, (key, query), (link, historyOn) ->
        val ui = HomeUiBuilder.build(entities, states, areas, RoomFilter.fromKey(key), link, Locale.getDefault(), labels, query, historyOn)
        // The selected chip may no longer exist (its last card was unhidden, its area emptied…); build()
        // already falls back to All for this state, but the saved key must follow, or a stale "hidden"
        // key comes back to life and jumps the screen back there as soon as something is hidden again.
        if (ui.selected.key != key) savedState[KEY_FILTER] = ui.selected.key
        ui
    }.conflate().flowOn(defaultDispatcher).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events: Flow<HomeEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            source.commandFailures.collect { _events.send(HomeEvent.NotConfirmed(it.uniqueId, it.command)) }
        }
    }

    private val editor = OverrideEditor(source)

    /** Saves the override sheet through [OverrideEditor.customize]; a failure is a [HomeEvent.SaveFailed]. */
    fun customize(id: String, newRooms: List<Area>, override: UiOverride) {
        viewModelScope.launch { if (!editor.customize(id, newRooms, override)) _events.send(HomeEvent.SaveFailed) }
    }

    /** Home search; blank ends it. */
    fun search(query: String) {
        savedState[KEY_QUERY] = query
    }

    fun select(filter: RoomFilter) {
        savedState[KEY_FILTER] = filter.key
    }

    fun tap(card: CardUi) {
        when (card.tap) {
            // Absolute, so "Retry" after a late confirmation repeats the intent instead of flipping it back.
            TapAction.TOGGLE -> send(card.id, if (card.on) Command.TurnOff else Command.TurnOn())
            TapAction.PRESS -> send(card.id, Command.Press)
            TapAction.OPEN_SHEET, TapAction.OPEN_HISTORY, TapAction.NONE -> Unit
        }
    }

    fun send(id: String, command: Command) {
        viewModelScope.launch {
            // A cached value may be outdated: never act on it (entity cache spec §3.6).
            if (state.value.card(id)?.stale == true) return@launch _events.send(HomeEvent.WaitingForConnection)
            val result = source.send(id, command)
            if (result is CommandResult.Rejected) _events.send(HomeEvent.Rejected(id, result.reason))
        }
    }

    private companion object {
        const val KEY_FILTER = "home.filter"
        const val KEY_QUERY = "home.query"
    }
}
