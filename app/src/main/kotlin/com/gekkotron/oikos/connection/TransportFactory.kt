package com.gekkotron.oikos.connection

import com.gekkotron.oikos.demo.DemoBroker
import com.gekkotron.oikos.transport.AthenaGatewayApi
import com.gekkotron.oikos.transport.DirectMqttTransport
import com.gekkotron.oikos.transport.GatewayTransport
import com.gekkotron.oikos.transport.ManagedTransport
import com.gekkotron.oikos.transport.TcpProbe
import com.gekkotron.oikos.transport.TransportSelector
import com.gekkotron.oikos.transport.TransportSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient

/** Builds `ActiveTransport`'s transport for a spec (spec §2; gateway spec §5). */
class TransportFactory(
    private val scope: CoroutineScope,
    private val networkChanges: Flow<Unit>,
    private val http: OkHttpClient,
) {
    fun create(spec: TransportSpec): ManagedTransport = when (spec) {
        TransportSpec.Demo -> DemoBroker(scope)
        is TransportSpec.Direct -> direct(spec)
        is TransportSpec.Gateway -> gateway(spec)
        is TransportSpec.Routed -> TransportSelector(
            spec,
            direct = ::direct,
            gateway = ::gateway,
            probe = TcpProbe(spec.direct.host, spec.direct.port),
            networkChanges = networkChanges,
            scope = scope,
        )
    }

    private fun direct(spec: TransportSpec.Direct): ManagedTransport = DirectMqttTransport(spec, scope)

    private fun gateway(spec: TransportSpec.Gateway): ManagedTransport = GatewayTransport(AthenaGatewayApi(spec, http), scope)
}
