package com.gekkotron.oikos.feature.widget

import com.gekkotron.oikos.feature.widget.store.WidgetConfigStore
import com.gekkotron.oikos.feature.widget.store.WidgetSnapshotStore
import com.gekkotron.oikos.feature.widget.work.WidgetScheduler

/** What the widget's Android entry points need; `OikosApplication` hands it out (same pattern as `PushComponent`). */
class WidgetComponent(
    val entities: WidgetEntities,
    val configs: WidgetConfigStore,
    val snapshots: WidgetSnapshotStore,
    val scheduler: WidgetScheduler,
    val refresher: WidgetRefresher,
)

interface WidgetComponentProvider {
    fun widgetComponent(): WidgetComponent
}
