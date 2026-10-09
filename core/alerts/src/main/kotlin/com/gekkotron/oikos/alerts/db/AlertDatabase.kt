package com.gekkotron.oikos.alerts.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Version 2 adds `alerts.locJson`; schemas exported to `core/alerts/schemas` (spec §6.1, localized alerts §5.1). */
@Database(
    entities = [AlertEntity::class, MetaEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AlertDatabase : RoomDatabase() {
    abstract fun alerts(): AlertDao

    companion object {
        const val FILE = "alerts.db"

        fun create(context: Context): AlertDatabase =
            Room.databaseBuilder(context.applicationContext, AlertDatabase::class.java, FILE).build()
    }
}
