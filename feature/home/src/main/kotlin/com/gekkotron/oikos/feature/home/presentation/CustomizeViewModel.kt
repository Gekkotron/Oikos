package com.gekkotron.oikos.feature.home.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.source.EntitySource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** What the override sheet opened from another tab has to show. */
sealed interface CustomizeLoad {
    /** The entity list has not arrived yet: show nothing, decide nothing. */
    data object Loading : CustomizeLoad

    /** The entity left (or never existed): the sheet closes. */
    data object Gone : CustomizeLoad

    data class Ready(val card: CardUi, val rooms: List<Area>) : CustomizeLoad
}

/** The override sheet for one entity, opened from a tab that does not own it (Security). */
@HiltViewModel
class CustomizeViewModel @Inject constructor(
    private val source: EntitySource,
    private val labels: LabelText,
) : ViewModel() {
    private val editor = OverrideEditor(source)

    fun load(id: String): Flow<CustomizeLoad> = combine(source.entities, source.states(), source.areas) { entities, states, areas ->
        when {
            entities.isEmpty() -> CustomizeLoad.Loading
            else -> HomeUiBuilder.cardFor(entities, states, areas, id, Locale.getDefault(), labels)
                ?.let { CustomizeLoad.Ready(it, areas.filter { a -> a.id != UNASSIGNED_AREA }) }
                ?: CustomizeLoad.Gone
        }
    }

    /** [onFailed] runs if the write fails; the sheet is already closed by then, so the caller's screen reports it. */
    fun save(id: String, newRooms: List<Area>, override: UiOverride, onFailed: () -> Unit) {
        viewModelScope.launch { if (!editor.customize(id, newRooms, override)) onFailed() }
    }

    /** The control sheet opened from another screen sends through the same source as Home. */
    fun send(id: String, command: Command) {
        viewModelScope.launch { source.send(id, command) }
    }
}
