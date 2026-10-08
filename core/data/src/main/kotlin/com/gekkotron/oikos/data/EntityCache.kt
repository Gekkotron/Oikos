package com.gekkotron.oikos.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** The last MQTT messages that built the house, for an instant cold start (entity cache spec §3.3). No secrets. */
interface EntityCache {
    /** The cached messages for [key], or empty when the cache belongs to another profile. */
    suspend fun load(key: String): Map<String, String>

    suspend fun save(key: String, messages: Map<String, String>)

    suspend fun clear()
}

@Serializable
internal data class StoredCache(val profileKey: String = "", val messages: Map<String, String> = emptyMap())

class FileEntityCache private constructor(private val store: DataStore<StoredCache>, scope: CoroutineScope) : EntityCache {
    init {
        // Read eagerly at launch, in parallel with the profile, so Home finds it warm (DataStore keeps it in memory).
        scope.launch { store.data.first() }
    }

    /**
     * Straight from DataStore, which reflects every completed [save]. A snapshot collected on the caller's scope could
     * still hold the value from before a save that just returned (seen on CI: a save, then an empty load).
     */
    override suspend fun load(key: String): Map<String, String> {
        val cache = store.data.first()
        return if (cache.profileKey == key) cache.messages else emptyMap()
    }

    override suspend fun save(key: String, messages: Map<String, String>) {
        store.updateData { StoredCache(key, messages) }
    }

    override suspend fun clear() {
        store.updateData { StoredCache() }
    }

    companion object {
        const val FILE = "entity-cache.json"

        /** In `noBackupFilesDir`, which is never backed up or transferred. */
        fun create(context: Context, scope: CoroutineScope): FileEntityCache = create(context.noBackupFilesDir.resolve(FILE), scope)

        fun create(file: File, scope: CoroutineScope): FileEntityCache {
            val store = DataStoreFactory.create(
                serializer = CacheSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler { StoredCache() },
                scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
                produceFile = { file },
            )
            return FileEntityCache(store, scope)
        }
    }
}

internal object CacheSerializer : Serializer<StoredCache> {
    private val json = Json { ignoreUnknownKeys = true }

    override val defaultValue: StoredCache = StoredCache()

    override suspend fun readFrom(input: InputStream): StoredCache = try {
        json.decodeFromString(StoredCache.serializer(), input.readBytes().decodeToString())
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("unreadable entity cache", e)
    }

    override suspend fun writeTo(t: StoredCache, output: OutputStream) {
        output.write(json.encodeToString(StoredCache.serializer(), t).encodeToByteArray())
    }
}
