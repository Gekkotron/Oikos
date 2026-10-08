package com.gekkotron.oikos.feature.widget.testing

import android.app.Application
import com.gekkotron.oikos.feature.widget.WidgetComponent
import com.gekkotron.oikos.feature.widget.WidgetComponentProvider
import com.gekkotron.oikos.feature.widget.WidgetRefresher

/** An application that hands out a widget component built from fakes; [scheduler] records what the UI asked for. */
class TestWidgetApp : Application(), WidgetComponentProvider {
    val scheduler = FakeScheduler()
    private val entities = FakeWidgetEntities()
    private val configs = MemConfigs()
    private val snapshots = MemSnapshots()
    private val component = WidgetComponent(entities, configs, snapshots, scheduler, WidgetRefresher(entities, configs, snapshots, {}))

    override fun widgetComponent(): WidgetComponent = component
}
