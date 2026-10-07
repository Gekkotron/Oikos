package com.gekkotron.oikos.alerts.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Version 1, schema exported to `core/alerts/schemas` (spec §6.1). */
@Database(entities = [AlertEntity::class, MetaEntity::class], version = 1, exportSchema = true)
abstract class AlertDatabase : RoomDatabase() {
    abstract fun alerts(): AlertDao

    companion object {
        const val FILE = "alerts.db"

        fun create(context: Context): AlertDatabase =
            Room.databaseBuilder(context.applicationContext, AlertDatabase::class.java, FILE).build()
    }
}
