package com.gekkotron.oikos.push

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

@Serializable
internal data class StoredRegistration(
    val token: String = "",
    val topic: String = "",
    val at: Long = 0,
    val legacyCleared: Boolean = false,
)

/** The last registration that reached the broker (push alerts spec §5.4). */
data class LastRegistration(val token: String, val topic: String, val at: Instant, val legacyCleared: Boolean = false)

/** Kept apart from the profile and preferences so "Set up again" can forget it on its own. */
class RegistrationStore internal constructor(private val store: DataStore<StoredRegistration>) {
    fun observe(): Flow<LastRegistration?> = store.data.map { stored ->
        stored.takeIf { it.token.isNotBlank() }?.let { LastRegistration(it.token, it.topic, Instant.ofEpochSecond(it.at), it.legacyCleared) }
    }

    suspend fun last(): LastRegistration? = observe().first()

    /** Preserves [legacyCleared], the one-shot flag for the hub takeover (hub spec §9). */
    suspend fun save(token: String, topic: String, at: Instant) {
        store.updateData { it.copy(token = token, topic = topic, at = at.epochSecond) }
    }

    /** True once the `oikos-push/tokens/<installId>` retained message has been cleared on the broker (hub spec §9). */
    suspend fun legacyCleared(): Boolean = store.data.first().legacyCleared

    suspend fun markLegacyCleared() {
        store.updateData { it.copy(legacyCleared = true) }
    }

    suspend fun clear() {
        store.updateData { StoredRegistration() }
    }

    companion object {
        const val FILE = "datastore/push-registration.json"

        fun create(context: Context, scope: CoroutineScope): RegistrationStore = create(context.filesDir.resolve(FILE), scope)

        fun create(file: File, scope: CoroutineScope): RegistrationStore = RegistrationStore(
            DataStoreFactory.create(
                serializer = RegistrationSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler { StoredRegistration() },
                scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
                produceFile = { file },
            ),
        )
    }
}

internal object RegistrationSerializer : Serializer<StoredRegistration> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: StoredRegistration = StoredRegistration()

    override suspend fun readFrom(input: InputStream): StoredRegistration = try {
        json.decodeFromString(StoredRegistration.serializer(), input.readBytes().decodeToString())
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("unreadable registration", e)
    }

    override suspend fun writeTo(t: StoredRegistration, output: OutputStream) {
        output.write(json.encodeToString(StoredRegistration.serializer(), t).encodeToByteArray())
    }
}
