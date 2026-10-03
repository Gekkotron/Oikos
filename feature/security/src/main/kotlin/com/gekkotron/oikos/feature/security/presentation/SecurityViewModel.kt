package com.gekkotron.oikos.feature.security.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.UiOverride
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SecurityViewModel internal constructor(
    private val source: EntitySource,
    dispatcher: CoroutineDispatcher,
) : ViewModel() {
    @Inject
    constructor(source: EntitySource) : this(source, Dispatchers.Default)

    // Like EnergyViewModel: one rebuild per collection turn, built off Main.
    val state: StateFlow<SecurityUi> = combine(source.entities, source.states(), source.areas, source.link, SecurityUiBuilder::build)
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
