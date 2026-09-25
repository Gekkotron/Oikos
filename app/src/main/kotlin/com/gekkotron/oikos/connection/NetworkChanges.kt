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
 */
fun Context.networkChanges(): Flow<Unit> = callbackFlow {
    val connectivity = getSystemService(ConnectivityManager::class.java)
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            trySend(Unit)
        }

        override fun onLost(network: Network) {
            trySend(Unit)
        }
    }
    connectivity.registerDefaultNetworkCallback(callback)
    awaitClose { connectivity.unregisterNetworkCallback(callback) }
}.conflate()
