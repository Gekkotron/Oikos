package com.gekkotron.oikos.feature.home.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.source.EntitySource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val source: EntitySource,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    val state: StateFlow<HomeUiState> = combine(
        source.entities,
        source.states(),
        source.areas,
        savedState.getStateFlow(KEY_FILTER, RoomFilter.All.key),
        source.link,
    ) { entities, states, areas, key, link ->
        HomeUiBuilder.build(entities, states, areas, RoomFilter.fromKey(key), link, Locale.getDefault())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events: Flow<HomeEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            source.commandFailures.collect { _events.send(HomeEvent.NotConfirmed(it.uniqueId, it.command)) }
        }
    }

    fun select(filter: RoomFilter) {
        savedState[KEY_FILTER] = filter.key
    }

    fun tap(card: CardUi) {
        when (card.tap) {
            // Absolute, so "Retry" after a late confirmation repeats the intent instead of flipping it back.
            TapAction.TOGGLE -> send(card.id, if (card.on) Command.TurnOff else Command.TurnOn())
            TapAction.PRESS -> send(card.id, Command.Press)
            TapAction.OPEN_SHEET, TapAction.NONE -> Unit
        }
    }

    fun send(id: String, command: Command) {
        viewModelScope.launch {
            val result = source.send(id, command)
            if (result is CommandResult.Rejected) _events.send(HomeEvent.Rejected(id, result.reason))
        }
    }

    private companion object {
        const val KEY_FILTER = "home.filter"
    }
}
