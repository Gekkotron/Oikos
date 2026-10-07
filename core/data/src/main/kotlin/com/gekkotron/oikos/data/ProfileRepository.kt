package com.gekkotron.oikos.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File
import java.util.Base64
import java.util.UUID

/** The saved connection profile (spec §4.2). Secrets are decrypted only into memory. */
class ProfileRepository internal constructor(
    private val store: DataStore<StoredProfile>,
    private val secrets: SecretStore,
    scope: CoroutineScope,
    /** Dispatcher [toState] (and its [SecretStore.decrypt] calls) run on; tests may substitute a deterministic test dispatcher. */
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    val state: StateFlow<ProfileState> =
        store.data.map(::toState).flowOn(ioDispatcher).stateIn(scope, SharingStarted.Eagerly, ProfileState.Loading)

    /** The route choice (gateway spec §4); `AUTO` until the user picks one. */
    val route: StateFlow<RoutePreference> =
        store.data.map { it.route }.stateIn(scope, SharingStarted.Eagerly, RoutePreference.AUTO)

    suspend fun save(profile: ConnectionProfile) {
        val id = installId()
        val stored = toStored(profile, id)
        store.updateData { stored }
    }

    /** Forgets the connection; the install id survives (the push plan keys registrations by it). */
    suspend fun clear() {
        val id = installId()
        store.updateData { StoredProfile(installId = id) }
    }

    /** The Rooms step was finished or skipped; it is not shown again for this profile. */
    suspend fun finishRooms() {
        store.updateData { it.copy(roomsPending = false) }
    }

    /** The notification step was finished or skipped; it is not shown again for this profile. */
    suspend fun finishNotifications() {
        store.updateData { it.copy(notificationsPending = false) }
    }

    /** Changes only the route; the profile, and so [state], stay equal. */
    suspend fun setRoute(route: RoutePreference) {
        store.updateData { it.copy(route = route) }
    }

    suspend fun installId(): String =
        store.updateData { if (it.installId.isEmpty()) it.copy(installId = newId()) else it }.installId

    private fun toState(s: StoredProfile): ProfileState = when (s.mode) {
        null -> ProfileState.None
        StoredMode.DEMO -> ProfileState.Ready(ConnectionProfile.Demo)
        StoredMode.DIRECT -> try {
            s.toDirect()?.let { ProfileState.Ready(it, s.roomsPending, s.notificationsPending) } ?: ProfileState.None
        } catch (e: SecretUnavailable) {
            ProfileState.Locked
        }
    }

    private fun StoredProfile.toDirect(): ConnectionProfile.Direct? {
        val l = local ?: return null
        return ConnectionProfile.Direct(
            local = LocalBroker(l.host, l.port, l.tls, l.ws, l.username, l.passCipher?.let { open(it).decodeToString() }),
            prefix = prefix,
            gateway = gateway?.let { GatewayConfig(it.url, open(it.keyCipher), it.brokerHost) },
            pushTokenTopic = pushTokenTopic,
        )
    }

    private fun toStored(p: ConnectionProfile, id: String): StoredProfile = when (p) {
        ConnectionProfile.Demo -> StoredProfile(installId = id, mode = StoredMode.DEMO)
        is ConnectionProfile.Direct -> StoredProfile(
            installId = id,
            mode = StoredMode.DIRECT,
            local = with(p.local) { StoredLocal(host, port, tls, ws, username, password?.let { seal(it.encodeToByteArray()) }) },
            gateway = p.gateway?.let { StoredGateway(it.url, it.brokerHost, seal(it.key)) },
            prefix = p.prefix,
            pushTokenTopic = p.pushTokenTopic,
            roomsPending = true,
            notificationsPending = true,
        )
    }

    private fun seal(plain: ByteArray): String = Base64.getEncoder().encodeToString(secrets.encrypt(plain))

    /**
     * Any failure to open a secret means Locked. [toState] runs inside [state]'s `stateIn` on the app
     * scope, so an exception escaping here (a Keystore ProviderException, say) would crash the process
     * at every launch.
     */
    private fun open(sealed: String): ByteArray = try {
        secrets.decrypt(Base64.getDecoder().decode(sealed))
    } catch (e: SecretUnavailable) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw SecretUnavailable("not base64", e)
    } catch (e: Exception) {
        throw SecretUnavailable("secret store failed", e)
    }

    companion object {
        const val FILE = "datastore/profile.json"

        fun create(context: Context, secrets: SecretStore, scope: CoroutineScope): ProfileRepository =
            create(context.filesDir.resolve(FILE), secrets, scope)

        fun create(file: File, secrets: SecretStore, scope: CoroutineScope): ProfileRepository {
            val store = DataStoreFactory.create(
                serializer = ProfileSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler { StoredProfile() },
                scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
                produceFile = { file },
            )
            return ProfileRepository(store, secrets, scope, ioDispatcher = Dispatchers.IO)
        }
    }
}
