package com.gekkotron.oikos.feature.energy.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.history.EnergyScale
import com.gekkotron.oikos.history.HistoryClient
import com.gekkotron.oikos.history.HistoryRef
import com.gekkotron.oikos.history.HistoryResult
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.transport.LinkState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EnergyViewModel internal constructor(
    private val source: EntitySource,
    dispatcher: CoroutineDispatcher,
    private val history: HistoryClient = HistoryClient.None,
    refresh: Duration = 5.minutes,
    clock: () -> Long,
) : ViewModel() {
    @Inject
    constructor(source: EntitySource, history: HistoryClient) :
        this(source, Dispatchers.Default, history, clock = System::currentTimeMillis)

    /**
     * Today's kWh by entity id ([EnergyToday.requests]), fetched as soon as entities are known and again every
     * [refresh] while the state is collected; an unanswered entity is simply absent. Empty without history.
     */
    private val today: Flow<Map<String, Double>> =
        combine(history.available, source.entities.map(EnergyToday::requests).distinctUntilChanged(), ::Pair)
            .flatMapLatest { (available, requests) ->
                if (!available || requests.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    flow {
                        while (true) {
                            emit(fetch(requests))
                            delay(refresh)
                        }
                    }
                }
            }
            // The screen doesn't wait on the first answer.
            .onStart { emit(emptyMap()) }

    private data class Inputs(
        val entities: Map<String, Entity>,
        val states: Map<String, EntityState>,
        val link: LinkState,
        val today: Map<String, Double>,
        val historyAvailable: Boolean,
    )

    // Like HomeViewModel: conflate() keeps one rebuild per collection turn and flowOn moves the build off
    // Main. scan carries the last consumer order into the next build, so the 5 s throttle (spec §4) survives.
    val state: StateFlow<EnergyUiState> = combine(source.entities, source.states(), source.link, today, history.available, ::Inputs)
        .conflate()
        .scan(EnergyUiState()) { last, i ->
            EnergyFlowBuilder.build(i.entities, i.states, i.link, clock(), last.order, i.today).copy(historyAvailable = i.historyAvailable)
        }
        .flowOn(dispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EnergyUiState())

    private suspend fun fetch(requests: Map<String, Pair<HistoryRef, EnergyScale>>): Map<String, Double> = coroutineScope {
        requests.map { (id, r) -> async { id to history.today(r.first, r.second) } }.awaitAll()
            .mapNotNull { (id, result) -> (result as? HistoryResult.Ok)?.value?.let { id to it } }
            .toMap()
    }

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
