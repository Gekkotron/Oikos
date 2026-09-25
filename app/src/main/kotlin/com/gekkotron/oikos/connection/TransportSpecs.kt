package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.GatewayConfig
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.data.RoutePreference
import com.gekkotron.oikos.transport.Route
import com.gekkotron.oikos.transport.TransportSpec

fun clientIdFor(installId: String): String = "oikos-" + installId.filter { it != '-' }.take(8)

/** The onboarding test connection has its own id: a duplicate id makes the broker kick the other client. */
fun setupClientIdFor(installId: String): String = clientIdFor(installId) + "-setup"

/** A profile with a gateway gets both routes and [route] picks between them (gateway spec §5). */
fun ProfileState.toSpec(clientId: String, route: RoutePreference = RoutePreference.AUTO): TransportSpec? = when (this) {
    is ProfileState.Ready -> when (val p = profile) {
        ConnectionProfile.Demo -> TransportSpec.Demo
        is ConnectionProfile.Direct -> {
            val direct = p.local.toSpec(clientId)
            p.gateway?.let { TransportSpec.Routed(direct, it.toSpec(p.local), route.toRoute()) } ?: direct
        }
    }
    ProfileState.Loading, ProfileState.None, ProfileState.Locked -> null
}

fun LocalBroker.toSpec(clientId: String): TransportSpec.Direct = TransportSpec.Direct(host, port, tls, ws, username, password, clientId)

/** The gateway logs in to the broker with the local credentials; they travel only inside the envelope. */
fun GatewayConfig.toSpec(local: LocalBroker): TransportSpec.Gateway =
    TransportSpec.Gateway(url, key, brokerHost, local.username, local.password)

fun RoutePreference.toRoute(): Route = when (this) {
    RoutePreference.AUTO -> Route.AUTO
    RoutePreference.DIRECT -> Route.DIRECT
    RoutePreference.GATEWAY -> Route.GATEWAY
}

fun Route.toPreference(): RoutePreference = when (this) {
    Route.AUTO -> RoutePreference.AUTO
    Route.DIRECT -> RoutePreference.DIRECT
    Route.GATEWAY -> RoutePreference.GATEWAY
}

/** The link sheet's route row: only a profile with a gateway has a choice to make; null hides the row. */
fun routeChoice(state: ProfileState, route: RoutePreference): Route? =
    ((state as? ProfileState.Ready)?.profile as? ConnectionProfile.Direct)?.gateway?.let { route.toRoute() }
