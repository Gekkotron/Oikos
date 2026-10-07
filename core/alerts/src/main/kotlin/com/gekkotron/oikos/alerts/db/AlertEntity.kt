package com.gekkotron.oikos.alerts.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Table `alerts`, mirroring [com.gekkotron.oikos.alerts.Alert]; actions are JSON, times epoch millis (spec §6.1). */
@Entity(tableName = "alerts", indices = [Index(value = ["channel", "receivedAt"])])
data class AlertEntity(
    @PrimaryKey val id: String,
    val channel: String,
    val severity: String,
    val title: String,
    val body: String,
    val imageUrl: String?,
    val videoUrl: String?,
    val entityId: String?,
    val actionsJson: String,
    val source: String?,
    val silent: Boolean,
    val resolved: Boolean,
    val sentAt: Long,
    val receivedAt: Long,
)

/** Small key/value rows, for the "Clear all" marker. */
@Entity(tableName = "alert_meta")
data class MetaEntity(@PrimaryKey val key: String, val value: Long)
