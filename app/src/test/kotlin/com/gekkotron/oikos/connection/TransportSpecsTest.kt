package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.GatewayConfig
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.data.RoutePreference
import com.gekkotron.oikos.transport.Route
import com.gekkotron.oikos.transport.TransportSpec
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class TransportSpecsTest {
    private val id = "3f2a9c1e-0b7d-4e5f-9a8b-1c2d3e4f5a6b"

    @Test
    fun `client ids are short, stable and distinct for the setup test`() {
        assertEquals("oikos-3f2a9c1e", clientIdFor(id))
        assertEquals("oikos-3f2a9c1e-setup", setupClientIdFor(id))
    }

    @Test
    fun `setup client id differs from the app client id`() = assertNotEquals(clientIdFor(id), setupClientIdFor(id))

    @Test
    fun `profile states map to specs`() {
        assertNull(ProfileState.Loading.toSpec("c"))
        assertNull(ProfileState.None.toSpec("c"))
        assertNull(ProfileState.Locked.toSpec("c"))
        assertEquals(TransportSpec.Demo, ProfileState.Ready(ConnectionProfile.Demo).toSpec("c"))
        val local = LocalBroker("broker.local", 8883, tls = true, ws = false, username = "u", password = "p")
        assertEquals(
            TransportSpec.Direct("broker.local", 8883, tls = true, ws = false, username = "u", password = "p", clientId = "c"),
            ProfileState.Ready(ConnectionProfile.Direct(local)).toSpec("c"),
        )
    }

    private val key = ByteArray(32) { it.toByte() }
    private val local = LocalBroker("192.168.1.91", 1883, username = "oikos", password = "pw")
    private val withGateway = ConnectionProfile.Direct(local, gateway = GatewayConfig("https://gw.example", key, "192.168.1.91"))

    @Test
    fun `a profile with a gateway maps to a routed spec carrying the local credentials`() {
        val spec = ProfileState.Ready(withGateway).toSpec("c", RoutePreference.GATEWAY)
        assertEquals(
            TransportSpec.Routed(
                TransportSpec.Direct("192.168.1.91", 1883, tls = false, ws = false, username = "oikos", password = "pw", clientId = "c"),
                TransportSpec.Gateway("https://gw.example", key, "192.168.1.91", "oikos", "pw"),
                Route.GATEWAY,
            ),
            spec,
        )
    }

    @Test
    fun `a profile without a gateway ignores the route`() {
        val plain = ConnectionProfile.Direct(local)
        assertEquals(ProfileState.Ready(plain).toSpec("c"), ProfileState.Ready(plain).toSpec("c", RoutePreference.GATEWAY))
    }

    @Test
    fun `a route change changes the spec but not the profile`() {
        val state = ProfileState.Ready(withGateway)
        assertNotEquals(state.toSpec("c", RoutePreference.AUTO), state.toSpec("c", RoutePreference.DIRECT))
    }

    @Test
    fun `route choice is offered only for a profile with a gateway`() {
        assertEquals(Route.DIRECT, routeChoice(ProfileState.Ready(withGateway), RoutePreference.DIRECT))
        assertNull(routeChoice(ProfileState.Ready(ConnectionProfile.Direct(local)), RoutePreference.DIRECT))
        assertNull(routeChoice(ProfileState.Ready(ConnectionProfile.Demo), RoutePreference.AUTO))
        assertNull(routeChoice(ProfileState.None, RoutePreference.AUTO))
    }

    @Test
    fun `route and preference map both ways`() {
        Route.entries.forEach { assertEquals(it, it.toPreference().toRoute()) }
    }
}
