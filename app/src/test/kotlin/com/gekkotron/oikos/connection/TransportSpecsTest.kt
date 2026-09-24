package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.data.ProfileState
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
}
