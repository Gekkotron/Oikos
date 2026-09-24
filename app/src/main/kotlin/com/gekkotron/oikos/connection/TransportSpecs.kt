package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.transport.TransportSpec

fun clientIdFor(installId: String): String = "oikos-" + installId.filter { it != '-' }.take(8)

/** The onboarding test connection has its own id: a duplicate id makes the broker kick the other client. */
fun setupClientIdFor(installId: String): String = clientIdFor(installId) + "-setup"

fun ProfileState.toSpec(clientId: String): TransportSpec? = when (this) {
    is ProfileState.Ready -> when (val p = profile) {
        ConnectionProfile.Demo -> TransportSpec.Demo
        is ConnectionProfile.Direct -> p.local.toSpec(clientId)
    }
    ProfileState.Loading, ProfileState.None, ProfileState.Locked -> null
}

fun LocalBroker.toSpec(clientId: String): TransportSpec.Direct = TransportSpec.Direct(host, port, tls, ws, username, password, clientId)
