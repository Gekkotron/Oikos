package com.gekkotron.oikos.feature.energy.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.source.EntitySource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EnergyViewModel internal constructor(
    private val source: EntitySource,
    dispatcher: CoroutineDispatcher,
    clock: () -> Long,
) : ViewModel() {
    @Inject
    constructor(source: EntitySource) : this(source, Dispatchers.Default, System::currentTimeMillis)

    // Like HomeViewModel: conflate() keeps one rebuild per collection turn and flowOn moves the build off
    // Main. scan carries the last consumer order into the next build, so the 5 s throttle (spec §4) survives.
    val state: StateFlow<EnergyUiState> = combine(source.entities, source.states(), source.link, ::Triple)
        .conflate()
        .scan(EnergyUiState()) { last, (entities, states, link) -> EnergyFlowBuilder.build(entities, states, link, clock(), last.order) }
        .flowOn(dispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EnergyUiState())

    private val _events = Channel<EnergyEvent>(Channel.BUFFERED)
    val events: Flow<EnergyEvent> = _events.receiveAsFlow()

    /** Writes the sensor's override from the details sheet; an offline broker becomes [EnergyEvent.SaveFailed]. */
    fun save(id: String, edit: SensorEdit) {
        val entity = source.entities.value[id] ?: return
        viewModelScope.launch {
            // Like Home's OverrideEditor: offline (PublishFailed) or a bad id becomes an event, never a crash.
            try {
                source.setOverride(id, EnergyEdit.apply(entity, edit))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(EnergyEvent.SaveFailed)
            }
        }
    }
}

sealed interface EnergyEvent {
    data object SaveFailed : EnergyEvent
}
