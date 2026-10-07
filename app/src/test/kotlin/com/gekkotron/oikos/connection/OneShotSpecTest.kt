package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.GatewayConfig
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.SecretStore
import com.gekkotron.oikos.transport.Route
import com.gekkotron.oikos.transport.TransportSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class OneShotSpecTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val passThrough = object : SecretStore {
        override fun encrypt(plain: ByteArray) = plain.copyOf()
        override fun decrypt(cipher: ByteArray) = cipher.copyOf()
    }

    @After
    fun tearDown() = scope.cancel()

    private fun repository() = ProfileRepository.create(tmp.newFile().also { it.delete() }, passThrough, scope)

    @Test
    fun `no profile gives no spec`(): Unit = runBlocking {
        assertNull(repository().oneShotSpec())
    }

    @Test
    fun `a direct profile gets its own client id, never the live one`(): Unit = runBlocking {
        val profiles = repository()
        profiles.save(ConnectionProfile.Direct(LocalBroker("192.168.1.91")))
        val live = clientIdFor(profiles.installId())
        val spec = assertIs<TransportSpec.Direct>(profiles.oneShotSpec())
        assertNotEquals(live, spec.clientId)
        assertEquals("$live-push", spec.clientId)
    }

    @Test
    fun `a routed profile keeps the route and gives the direct leg its own client id`(): Unit = runBlocking {
        val profiles = repository()
        profiles.save(ConnectionProfile.Direct(LocalBroker("192.168.1.91"), gateway = GatewayConfig("https://gw.example", ByteArray(32), "192.168.1.91")))
        val live = clientIdFor(profiles.installId())
        val spec = assertIs<TransportSpec.Routed>(profiles.oneShotSpec())
        assertEquals(Route.AUTO, spec.route)
        assertNotEquals(live, spec.direct.clientId)
    }
}
