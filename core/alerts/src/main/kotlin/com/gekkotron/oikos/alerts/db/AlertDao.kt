package com.gekkotron.oikos.alerts.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Spec §6.1. An abstract class, not an interface: the transactions have bodies. */
@Dao
abstract class AlertDao {
    @Query("SELECT receivedAt FROM alerts WHERE id = :id")
    abstract suspend fun receivedAtOf(id: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(entity: AlertEntity)

    @Query("UPDATE alerts SET resolved = 1 WHERE id = :id")
    abstract suspend fun markResolved(id: String)

    /** Keeps the [keep] newest rows by `sentAt`. */
    @Query("DELETE FROM alerts WHERE id NOT IN (SELECT id FROM alerts ORDER BY sentAt DESC, id LIMIT :keep)")
    abstract suspend fun trim(keep: Int)

    @Query("SELECT * FROM alerts WHERE channel IN (:channels) ORDER BY receivedAt DESC, id LIMIT :limit")
    abstract fun recent(channels: List<String>, limit: Int): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts WHERE (:channel IS NULL OR channel = :channel) ORDER BY receivedAt DESC, id")
    abstract fun all(channel: String?): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts WHERE id = :id")
    abstract fun byId(id: String): Flow<AlertEntity?>

    @Query("DELETE FROM alerts")
    abstract suspend fun deleteAll()

    @Query("SELECT value FROM alert_meta WHERE `key` = :key")
    abstract suspend fun meta(key: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putMeta(meta: MetaEntity)

    @Query("DELETE FROM alert_meta WHERE `key` = :key")
    abstract suspend fun deleteMeta(key: String)

    /** Replaces the alert with the same id but keeps its first `receivedAt`, then trims. One transaction. */
    @Transaction
    open suspend fun upsert(entity: AlertEntity) {
        val kept = receivedAtOf(entity.id)
        insert(if (kept == null) entity else entity.copy(receivedAt = kept))
        trim(KEEP)
    }

    /**
     * History catch-up: inserts only an id we do not have, and not one sent before the user last cleared.
     * Returns whether the row is still there after the trim (an alert older than the 200th is not).
     */
    @Transaction
    open suspend fun insertIfMissing(entity: AlertEntity): Boolean {
        if (receivedAtOf(entity.id) != null) return false
        val clearedAt = meta(CLEARED_AT) ?: Long.MIN_VALUE
        if (entity.sentAt <= clearedAt) return false
        insert(entity)
        trim(KEEP)
        return receivedAtOf(entity.id) != null
    }

    /** The user's "Clear all": deletes every row and remembers [now], so history cannot bring them back. */
    @Transaction
    open suspend fun clearAll(now: Long) {
        deleteAll()
        putMeta(MetaEntity(CLEARED_AT, now))
    }

    /** "Set up again": a clean slate, marker included. */
    @Transaction
    open suspend fun reset() {
        deleteAll()
        deleteMeta(CLEARED_AT)
    }

    companion object {
        const val KEEP = 200
        const val CLEARED_AT = "clearedAt"
    }
}
