package com.gekkotron.oikos.feature.widget.testing

import com.gekkotron.oikos.feature.widget.CatalogResult
import com.gekkotron.oikos.feature.widget.Failure
import com.gekkotron.oikos.feature.widget.RefreshResult
import com.gekkotron.oikos.feature.widget.SendResult
import com.gekkotron.oikos.feature.widget.WidgetEntities
import com.gekkotron.oikos.feature.widget.model.WidgetCommand
import com.gekkotron.oikos.feature.widget.work.WidgetScheduler
import com.gekkotron.oikos.model.Command

/** A [WidgetEntities] with canned results. */
class FakeWidgetEntities(
    var refresh: RefreshResult = RefreshResult.Failed(Failure.OFFLINE),
    var catalog: CatalogResult = CatalogResult.Failed(Failure.OFFLINE),
) : WidgetEntities {
    override suspend fun refresh(ids: Set<String>) = refresh
    override suspend fun send(id: String, command: Command): SendResult = SendResult.NotConfirmed
    override suspend fun catalog() = catalog
}

/** Records what a [WidgetScheduler] was asked to do. */
class FakeScheduler : WidgetScheduler {
    var periodic = 0
    var cancelled = 0
    var now = 0
    val sent = mutableListOf<Triple<String, WidgetCommand, String?>>()
    override fun ensurePeriodic() { periodic++ }
    override fun cancelPeriodic() { cancelled++ }
    override fun refreshNow() { now++ }
    override fun send(id: String, command: WidgetCommand, code: String?) { sent += Triple(id, command, code) }
}
