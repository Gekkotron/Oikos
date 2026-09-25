package com.gekkotron.oikos.feature.home.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
) : ViewModel() {
    // conflate() collapses a burst of state messages to one rebuild per collection turn instead of one
    // per message, and flowOn moves the combine (and HomeUiBuilder.build) off Main; the final value is
    // never dropped.
    val state: StateFlow<HomeUiState> = combine(
        source.entities,
        source.states(),
        source.areas,
        savedState.getStateFlow(KEY_FILTER, RoomFilter.All.key),
        source.link,
    ) { entities, states, areas, key, link ->
        HomeUiBuilder.build(entities, states, areas, RoomFilter.fromKey(key), link, Locale.getDefault())
    }.conflate().flowOn(defaultDispatcher).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events: Flow<HomeEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            source.commandFailures.collect { _events.send(HomeEvent.NotConfirmed(it.uniqueId, it.command)) }
        }
    }

    private val editor = OverrideEditor(source)

    fun customize(id: String, override: UiOverride) {
        viewModelScope.launch { if (!editor.save(id, override)) _events.send(HomeEvent.SaveFailed) }
    }

    /**
     * @return the id to select for a typed room name: an existing room's (no write), or a new one's,
     * written in the background. It is returned at once, so the sheet can select it before the source
     * has echoed it. The source applies the areas locally first, so a second call sees the first room.
     */
    fun createRoom(name: String): String? {
        val resolved = AreaNames.resolve(name, source.areas.value) ?: return null
        if (resolved.isNew) viewModelScope.launch { if (!editor.addRoom(resolved.area)) _events.send(HomeEvent.SaveFailed) }
        return resolved.area.id
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
