package com.gekkotron.oikos.feature.widget.testing

import com.gekkotron.oikos.feature.widget.model.WidgetConfig
import com.gekkotron.oikos.feature.widget.model.WidgetSnapshot
import com.gekkotron.oikos.feature.widget.store.WidgetConfigStore
import com.gekkotron.oikos.feature.widget.store.WidgetSnapshotStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [WidgetConfigStore] for tests. */
class MemConfigs(val map: MutableMap<Int, WidgetConfig> = mutableMapOf()) : WidgetConfigStore {
    override val configs = MutableStateFlow<Map<Int, WidgetConfig>>(map.toMap())
    override suspend fun get(appWidgetId: Int) = map[appWidgetId]
    override suspend fun put(appWidgetId: Int, config: WidgetConfig) {
        map[appWidgetId] = config
        configs.value = map.toMap()
    }
    override suspend fun remove(appWidgetIds: IntArray) {
        appWidgetIds.forEach(map::remove)
        configs.value = map.toMap()
    }
    override suspend fun allIds(): Set<String> = map.values.flatMapTo(linkedSetOf()) { it.uniqueIds }
}

/** In-memory [WidgetSnapshotStore] for tests. */
class MemSnapshots : WidgetSnapshotStore {
    val flow = MutableStateFlow(WidgetSnapshot())
    override val snapshot: Flow<WidgetSnapshot> = flow
    override suspend fun current() = flow.value
    override suspend fun update(transform: (WidgetSnapshot) -> WidgetSnapshot) {
        flow.value = transform(flow.value)
    }
    override suspend fun clear() {
        flow.value = WidgetSnapshot()
    }
}
