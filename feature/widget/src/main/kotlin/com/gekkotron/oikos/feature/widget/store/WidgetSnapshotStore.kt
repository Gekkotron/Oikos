package com.gekkotron.oikos.feature.widget.store

import android.content.Context
import androidx.datastore.core.DataStore
import com.gekkotron.oikos.feature.widget.model.LinkStatus
import com.gekkotron.oikos.feature.widget.model.WidgetSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.File

/** Display data for every tile, plus the link status for the footer (widgets spec §3.2). No secrets. */
interface WidgetSnapshotStore {
    val snapshot: Flow<WidgetSnapshot>

    suspend fun current(): WidgetSnapshot

    suspend fun update(transform: (WidgetSnapshot) -> WidgetSnapshot)

    suspend fun clear()
}

/** "Set up again": no tiles, and the footer asks to set Oikos up (widgets spec §4). The config is kept. */
suspend fun WidgetSnapshotStore.resetForSetUp() = update { WidgetSnapshot(link = LinkStatus.NOT_CONFIGURED) }

class FileWidgetSnapshotStore private constructor(private val store: DataStore<WidgetSnapshot>) : WidgetSnapshotStore {
    override val snapshot: Flow<WidgetSnapshot> = store.data

    override suspend fun current(): WidgetSnapshot = store.data.first()

    override suspend fun update(transform: (WidgetSnapshot) -> WidgetSnapshot) {
        store.updateData(transform)
    }

    override suspend fun clear() {
        store.updateData { WidgetSnapshot() }
    }

    companion object {
        const val FILE = "widget-snapshot.json"

        /** In `noBackupFilesDir`, which is never backed up or transferred. */
        fun create(context: Context, scope: CoroutineScope): FileWidgetSnapshotStore = create(context.noBackupFilesDir.resolve(FILE), scope)

        fun create(file: File, scope: CoroutineScope): FileWidgetSnapshotStore = FileWidgetSnapshotStore(jsonDataStore(file, scope, WidgetSnapshot.serializer(), WidgetSnapshot()))
    }
}
