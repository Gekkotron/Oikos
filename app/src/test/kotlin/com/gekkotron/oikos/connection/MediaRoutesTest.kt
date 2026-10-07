package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.GatewayConfig
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.data.RoutePreference
import okhttp3.OkHttpClient
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MediaRoutesTest {
    private val http = OkHttpClient()
    private val withGateway = ConnectionProfile.Direct(
        LocalBroker("192.168.1.91"),
        gateway = GatewayConfig("https://gw.example", ByteArray(32), "192.168.1.91"),
    )
    private val localOnly = ConnectionProfile.Direct(LocalBroker("192.168.1.91"))

    @Test
    fun `auto offers both the LAN probe and the gateway`() {
        val route = mediaRouteFor(withGateway, RoutePreference.AUTO, http)
        assertNotNull(route.local)
        assertNotNull(route.gateway)
    }

    @Test
    fun `direct-only has no gateway and gateway-only has no LAN probe`() {
        mediaRouteFor(withGateway, RoutePreference.DIRECT, http).let {
            assertNotNull(it.local)
            assertNull(it.gateway)
        }
        mediaRouteFor(withGateway, RoutePreference.GATEWAY, http).let {
            assertNull(it.local)
            assertNotNull(it.gateway)
        }
    }

    @Test
    fun `a profile without a gateway has only the LAN`() {
        val route = mediaRouteFor(localOnly, RoutePreference.AUTO, http)
        assertNotNull(route.local)
        assertNull(route.gateway)
    }

    @Test
    fun `the demo and no profile have no route`() {
        for (profile in listOf(ConnectionProfile.Demo, null)) {
            val route = mediaRouteFor(profile, RoutePreference.AUTO, http)
            assertNull(route.local)
            assertNull(route.gateway)
        }
    }
}
