package com.gekkotron.oikos.connection

import com.gekkotron.oikos.feature.widget.TileMapper
import com.gekkotron.oikos.feature.widget.WidgetUpdater
import com.gekkotron.oikos.feature.widget.model.LinkStatus
import com.gekkotron.oikos.feature.widget.store.WidgetConfigStore
import com.gekkotron.oikos.feature.widget.store.WidgetSnapshotStore
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.source.EntitySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * While the app is connected, keeps the widgets live from the foreground source at no extra traffic (widgets spec §3.2).
 * It never declares a tile "Removed": only a settled headless refresh does, so that rule lives in one place.
 */
class WidgetMirror(
    /** The connected foreground source, or null while the app is not connected. */
    private val sources: Flow<EntitySource?>,
    private val configs: WidgetConfigStore,
    private val snapshots: WidgetSnapshotStore,
    private val updater: WidgetUpdater,
    private val historyEnabled: () -> Boolean,
    private val scope: CoroutineScope,
    private val debounce: Duration = 1.seconds,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Wanted(val ids: Set<String>, val entities: Map<String, Entity>, val states: Map<String, EntityState>)

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start() {
        scope.launch {
            sources
                .flatMapLatest { source ->
                    if (source == null) emptyFlow()
                    else combine(source.entities, source.states(), configs.configs) { entities, states, widgets ->
                        val ids = widgets.values.flatMapTo(linkedSetOf()) { it.uniqueIds }
                        Wanted(ids, entities.filterKeys { it in ids }, states.filterKeys { it in ids })
                    }
                }
                .filter { it.ids.isNotEmpty() }
                .distinctUntilChanged { a, b -> a.ids == b.ids && a.entities == b.entities && a.states == b.states }
                .debounce(debounce)
                .collect { wanted ->
                    snapshots.update { snapshot ->
                        val tiles = wanted.ids.mapNotNull { id ->
                            TileMapper.map(wanted.entities[id], wanted.states[id], snapshot.tiles[id], historyEnabled(), complete = false)?.let { id to it }
                        }.toMap()
                        snapshot.copy(tiles = tiles, link = LinkStatus.FRESH, lastFresh = now())
                    }
                    updater.updateAll()
                }
        }
    }
}
