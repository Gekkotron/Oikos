package com.gekkotron.oikos.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** The theme the user picked on this phone (settings spec §3). Mirrors `ThemeChoice` without depending on the design system. */
enum class ThemePreference { SYSTEM, NIGHT_GLASS, WARM_MINIMAL }

/** The theme is kept as a name so a value written by a newer app decodes as System instead of corrupting the file. */
@Serializable
internal data class StoredPreferences(val theme: String = ThemePreference.SYSTEM.name)

/** Per-phone preferences, kept apart from the profile so "Set up again" leaves them alone. */
class AppPreferences internal constructor(private val store: DataStore<StoredPreferences>, scope: CoroutineScope) {
    /** null until the file has been read. */
    val theme: StateFlow<ThemePreference?> = store.data
        .map { stored -> ThemePreference.entries.firstOrNull { it.name == stored.theme } ?: ThemePreference.SYSTEM }
        .stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun setTheme(theme: ThemePreference) {
        store.updateData { it.copy(theme = theme.name) }
    }

    companion object {
        const val FILE = "datastore/app-preferences.json"

        fun create(context: Context, scope: CoroutineScope): AppPreferences = create(context.filesDir.resolve(FILE), scope)

        fun create(file: File, scope: CoroutineScope): AppPreferences {
            val store = DataStoreFactory.create(
                serializer = PreferencesSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler { StoredPreferences() },
                scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
                produceFile = { file },
            )
            return AppPreferences(store, scope)
        }
    }
}

internal object PreferencesSerializer : Serializer<StoredPreferences> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: StoredPreferences = StoredPreferences()

    /** `SerializationException` is an `IllegalArgumentException`, so this covers malformed JSON too. */
    override suspend fun readFrom(input: InputStream): StoredPreferences = try {
        json.decodeFromString(StoredPreferences.serializer(), input.readBytes().decodeToString())
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("unreadable preferences", e)
    }

    override suspend fun writeTo(t: StoredPreferences, output: OutputStream) {
        output.write(json.encodeToString(StoredPreferences.serializer(), t).encodeToByteArray())
    }
}
