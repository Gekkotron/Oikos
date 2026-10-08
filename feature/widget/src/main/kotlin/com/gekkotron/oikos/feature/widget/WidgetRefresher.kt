package com.gekkotron.oikos.feature.widget

import com.gekkotron.oikos.feature.widget.model.LinkStatus
import com.gekkotron.oikos.feature.widget.model.TileStatus
import com.gekkotron.oikos.feature.widget.model.WidgetCommand
import com.gekkotron.oikos.feature.widget.model.WidgetSnapshot
import com.gekkotron.oikos.feature.widget.store.WidgetConfigStore
import com.gekkotron.oikos.feature.widget.store.WidgetSnapshotStore

/** Redraws every placed widget. */
fun interface WidgetUpdater {
    suspend fun updateAll()
}

/** The refresh and command logic behind the workers (widgets spec §3.4); workers stay thin so this is testable without WorkManager. */
class WidgetRefresher(
    private val entities: WidgetEntities,
    private val configs: WidgetConfigStore,
    private val snapshots: WidgetSnapshotStore,
    private val updater: WidgetUpdater,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun refresh() {
        val ids = configs.allIds()
        if (ids.isEmpty()) return
        when (val result = entities.refresh(ids)) {
            is RefreshResult.Ok -> snapshots.update { snap ->
                val tiles = ids.mapNotNull { id ->
                    TileMapper.map(result.entities[id], result.states[id], snap.tiles[id], result.historyEnabled, result.complete)?.let { id to it }
                }.toMap()
                snap.copy(tiles = tiles, link = LinkStatus.FRESH, lastFresh = now())
            }
            is RefreshResult.Failed -> snapshots.update { it.copy(link = result.reason.toLink()) }
        }
        updater.updateAll()
    }

    /** [code] goes into the command only; it is never written to the snapshot. */
    suspend fun send(id: String, command: WidgetCommand, code: String?) {
        val at = now()
        snapshots.update { snap ->
            val tile = snap.tiles[id] ?: return@update snap
            snap.copy(tiles = snap.tiles + (id to tile.copy(status = TileStatus.PENDING, pendingSince = at)))
        }
        updater.updateAll()
        val result = entities.send(id, command.toCommand(code))
        snapshots.update { snap ->
            when (result) {
                is SendResult.Confirmed -> {
                    val tile = TileMapper.map(result.entity, result.state, snap.tiles[id], result.historyEnabled, complete = false)
                    if (tile == null) snap else snap.copy(tiles = snap.tiles + (id to tile), link = LinkStatus.FRESH, lastFresh = now())
                }
                SendResult.NotConfirmed -> snap.markFailed(id)
                is SendResult.Failed -> snap.markFailed(id).copy(link = result.reason.toLink())
            }
        }
        updater.updateAll()
    }

    /** The command could not be sent at all (its code was lost): show "!" on the tile. */
    suspend fun fail(id: String) {
        snapshots.update { it.markFailed(id) }
        updater.updateAll()
    }

    private fun WidgetSnapshot.markFailed(id: String): WidgetSnapshot =
        tiles[id]?.let { copy(tiles = tiles + (id to it.copy(status = TileStatus.FAILED, pendingSince = null))) } ?: this

    private fun Failure.toLink(): LinkStatus = when (this) {
        Failure.OFFLINE -> LinkStatus.OFFLINE
        Failure.AUTH_FAILED -> LinkStatus.AUTH_FAILED
        Failure.NOT_CONFIGURED -> LinkStatus.NOT_CONFIGURED
    }
}
