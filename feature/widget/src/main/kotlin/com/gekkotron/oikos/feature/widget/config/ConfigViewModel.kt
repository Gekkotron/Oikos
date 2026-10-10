package com.gekkotron.oikos.feature.widget.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.feature.widget.CatalogResult
import com.gekkotron.oikos.feature.widget.Failure
import com.gekkotron.oikos.feature.widget.WidgetEntities
import com.gekkotron.oikos.feature.widget.model.WidgetConfig
import com.gekkotron.oikos.feature.widget.store.WidgetConfigStore
import com.gekkotron.oikos.feature.widget.work.WidgetScheduler
import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.Kind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PickerRow(val id: String, val name: String, val areaName: String, val kind: Kind, val alarmModes: Set<AlarmMode>)

data class ConfigUiState(
    val loading: Boolean = true,
    val offline: Boolean = false,
    val notConfigured: Boolean = false,
    val query: String = "",
    /** Filtered by [query], ordered by area then name. */
    val rows: List<PickerRow> = emptyList(),
    /** Every row, unfiltered: the "on the widget" section needs names even for rows the search hides. */
    val allRows: List<PickerRow> = emptyList(),
    /** In display order on the widget. */
    val selected: List<String> = emptyList(),
    val armMode: AlarmMode? = null,
    val fitCount: Int = WidgetConfig.MAX_ENTITIES,
) {
    /** The selected alarm panel, if any: its arm mode is chosen here (widgets spec §2.1). */
    val alarmRow: PickerRow? get() = allRows.firstOrNull { it.kind == Kind.ALARM_PANEL && it.id in selected }
}

/** The entity picker shown when a widget is placed or reconfigured (widgets spec §2.1). */
class ConfigViewModel(
    private val appWidgetId: Int,
    private val entities: WidgetEntities,
    private val configs: WidgetConfigStore,
    private val scheduler: WidgetScheduler,
    fitCount: Int,
) : ViewModel() {
    private val _state = MutableStateFlow(ConfigUiState(fitCount = fitCount))
    val state: StateFlow<ConfigUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val existing = configs.get(appWidgetId)
            when (val result = entities.catalog()) {
                is CatalogResult.Ok -> {
                    val order = result.areas.mapIndexed { i, area -> area.id to i }.toMap()
                    val names = result.areas.associate { it.id to it.name }
                    val rows = result.entities
                        .filter { !it.ui.hidden && it.partOf == null && it.kind != Kind.VACUUM } // no vacuum tiles (vacuum spec)
                        .sortedWith(compareBy({ order[it.area] ?: Int.MAX_VALUE }, { it.name.lowercase() }))
                        .map { PickerRow(it.uniqueId, it.name, names[it.area] ?: it.area, it.kind, it.capabilities.alarmModes) }
                    _state.update { it.copy(loading = false, rows = rows, allRows = rows, selected = existing?.uniqueIds.orEmpty(), armMode = existing?.armMode) }
                }
                is CatalogResult.Failed -> _state.update {
                    it.copy(
                        loading = false,
                        offline = result.reason == Failure.OFFLINE,
                        notConfigured = result.reason == Failure.NOT_CONFIGURED,
                        selected = existing?.uniqueIds.orEmpty(),
                        armMode = existing?.armMode,
                    )
                }
            }
        }
    }

    fun search(query: String) = _state.update { s ->
        val rows = if (query.isBlank()) s.allRows else s.allRows.filter { it.name.contains(query, ignoreCase = true) || it.areaName.contains(query, ignoreCase = true) }
        s.copy(query = query, rows = rows)
    }

    fun toggle(id: String) = _state.update { s ->
        when {
            id in s.selected -> s.copy(selected = s.selected - id)
            s.selected.size >= WidgetConfig.MAX_ENTITIES -> s
            else -> s.copy(selected = s.selected + id)
        }
    }

    fun move(from: Int, to: Int) = _state.update { s ->
        if (from !in s.selected.indices || to !in s.selected.indices) s
        else s.copy(selected = s.selected.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun setArmMode(mode: AlarmMode) = _state.update { it.copy(armMode = mode) }

    /** False when nothing is selected: an empty widget is not a valid placement. */
    suspend fun save(): Boolean {
        val s = _state.value
        if (s.selected.isEmpty()) return false
        configs.put(appWidgetId, WidgetConfig(s.selected, s.armMode))
        scheduler.ensurePeriodic()
        scheduler.refreshNow()
        return true
    }
}
