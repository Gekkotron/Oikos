package com.gekkotron.oikos.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * One event per default-network change (parent spec §4.5 step 1). No SSID is read, so no location
 * permission; `ACCESS_NETWORK_STATE` is enough. Each collector registers its own callback.
 *
 * Registering calls onAvailable at once for the network that is already the default. That is no change,
 * and emitting it would make the selector forget rejected direct credentials and try them again, so an
 * onAvailable for the network last seen is skipped; after onLost the same network counts as new again.
 */
fun Context.networkChanges(): Flow<Unit> = callbackFlow {
    val connectivity = getSystemService(ConnectivityManager::class.java)
    val callback = object : ConnectivityManager.NetworkCallback() {
        @Volatile
        private var last: Network? = connectivity.activeNetwork

        override fun onAvailable(network: Network) {
            if (network == last) return
            last = network
            trySend(Unit)
        }

        override fun onLost(network: Network) {
            if (network == last) last = null
            trySend(Unit)
        }
    }
    connectivity.registerDefaultNetworkCallback(callback)
    awaitClose { connectivity.unregisterNetworkCallback(callback) }
}.conflate()
