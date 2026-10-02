package com.gekkotron.oikos.feature.energy.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.source.EntitySource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class EnergyViewModel internal constructor(
    source: EntitySource,
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
}
