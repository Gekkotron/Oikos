package com.gekkotron.oikos.feature.widget.store

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.gekkotron.oikos.feature.widget.model.WidgetConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Which entities each placed widget shows (widgets spec §3.2). */
interface WidgetConfigStore {
    val configs: Flow<Map<Int, WidgetConfig>>

    suspend fun get(appWidgetId: Int): WidgetConfig?

    suspend fun put(appWidgetId: Int, config: WidgetConfig)

    suspend fun remove(appWidgetIds: IntArray)

    /** Every entity id on any widget. */
    suspend fun allIds(): Set<String>
}

@Serializable
internal data class StoredConfigs(val widgets: Map<Int, WidgetConfig> = emptyMap())

class FileWidgetConfigStore private constructor(private val store: DataStore<StoredConfigs>) : WidgetConfigStore {
    override val configs: Flow<Map<Int, WidgetConfig>> = store.data.map { it.widgets }

    override suspend fun get(appWidgetId: Int): WidgetConfig? = configs.first()[appWidgetId]

    override suspend fun put(appWidgetId: Int, config: WidgetConfig) {
        store.updateData { it.copy(widgets = it.widgets + (appWidgetId to config)) }
    }

    override suspend fun remove(appWidgetIds: IntArray) {
        store.updateData { it.copy(widgets = it.widgets - appWidgetIds.toSet()) }
    }

    override suspend fun allIds(): Set<String> = configs.first().values.flatMapTo(linkedSetOf()) { it.uniqueIds }

    companion object {
        const val FILE = "widget-config.json"

        /** In `noBackupFilesDir`, like the entity cache. */
        fun create(context: Context, scope: CoroutineScope): FileWidgetConfigStore = create(context.noBackupFilesDir.resolve(FILE), scope)

        fun create(file: File, scope: CoroutineScope): FileWidgetConfigStore = FileWidgetConfigStore(jsonDataStore(file, scope, StoredConfigs.serializer(), StoredConfigs()))
    }
}

internal fun <T> jsonDataStore(file: File, scope: CoroutineScope, strategy: KSerializer<T>, default: T): DataStore<T> = DataStoreFactory.create(
    serializer = JsonSerializer(strategy, default),
    corruptionHandler = ReplaceFileCorruptionHandler { default },
    scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
    produceFile = { file },
)

/** A JSON DataStore serializer; an unreadable file throws [CorruptionException] so the handler resets it. */
internal class JsonSerializer<T>(private val strategy: KSerializer<T>, override val defaultValue: T) : Serializer<T> {
    override suspend fun readFrom(input: InputStream): T = try {
        json.decodeFromString(strategy, input.readBytes().decodeToString())
    } catch (e: IllegalArgumentException) {
        // SerializationException is an IllegalArgumentException, and so is a WidgetConfig over the entity limit.
        throw CorruptionException("unreadable widget store", e)
    }

    override suspend fun writeTo(t: T, output: OutputStream) {
        output.write(json.encodeToString(strategy, t).encodeToByteArray())
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
