package com.gekkotron.oikos.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

@Serializable
internal enum class StoredMode { DEMO, DIRECT }

@Serializable
internal data class StoredLocal(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val ws: Boolean,
    val username: String? = null,
    val passCipher: String? = null,
)

@Serializable
internal data class StoredGateway(val url: String, val brokerHost: String? = null, val keyCipher: String)

/** `profile.json` (spec §4.1). Secret fields hold base64 [SecretStore] ciphertext. */
@Serializable
internal data class StoredProfile(
    val installId: String = "",
    val mode: StoredMode? = null,
    val local: StoredLocal? = null,
    val gateway: StoredGateway? = null,
    val prefix: String = DEFAULT_PREFIX,
    val pushTokenTopic: String? = null,
)

internal object ProfileSerializer : Serializer<StoredProfile> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: StoredProfile = StoredProfile()

    override suspend fun readFrom(input: InputStream): StoredProfile = try {
        json.decodeFromString(StoredProfile.serializer(), input.readBytes().decodeToString())
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("unreadable profile", e)
    }

    override suspend fun writeTo(t: StoredProfile, output: OutputStream) {
        output.write(json.encodeToString(StoredProfile.serializer(), t).encodeToByteArray())
    }
}
