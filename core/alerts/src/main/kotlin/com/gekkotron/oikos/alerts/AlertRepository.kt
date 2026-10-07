package com.gekkotron.oikos.alerts

import com.gekkotron.oikos.alerts.db.AlertDao
import com.gekkotron.oikos.alerts.db.AlertEntity
import com.gekkotron.oikos.alerts.db.toAlert
import com.gekkotron.oikos.alerts.db.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/** The alert history (spec §6.1). Everything the UI and the push service need from Room, as [Alert]. */
class AlertRepository(private val dao: AlertDao, private val clock: () -> Instant = Instant::now) {
    /** Stores or replaces the alert with this id (keeping its first `receivedAt`) and trims to 200 rows. */
    suspend fun upsert(alert: Alert) = dao.upsert(alert.toEntity())

    /** Catch-up insert; true when the alert is new and still stored after trimming. */
    suspend fun insertIfMissing(alert: Alert): Boolean = dao.insertIfMissing(alert.toEntity())

    suspend fun markResolved(id: String) = dao.markResolved(id)

    /** "Clear all" on this phone: also stops older history alerts from coming back. */
    suspend fun clearAll() = dao.clearAll(clock().toEpochMilli())

    /** "Set up again": every row and the clear marker. */
    suspend fun reset() = dao.reset()

    fun recent(channels: Set<AlertChannel>, limit: Int = 5): Flow<List<Alert>> =
        dao.recent(channels.map { it.id }, limit).map { rows -> rows.map(AlertEntity::toAlert) }

    fun all(channel: AlertChannel?): Flow<List<Alert>> = dao.all(channel?.id).map { rows -> rows.map(AlertEntity::toAlert) }

    fun byId(id: String): Flow<Alert?> = dao.byId(id).map { it?.toAlert() }
}
