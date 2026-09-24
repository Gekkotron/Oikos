package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.transport.testing.FakeMqttTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EntitySourceSessionTest {
    private val parent = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val scopes = mutableListOf<CoroutineScope>()
    private val prefixes = mutableListOf<String>()
    private val transport = FakeMqttTransport()
    private val session = EntitySourceSession(transport, parent) { transport, scope, prefix, diagnostics ->
        scopes += scope
        prefixes += prefix
        EntitySourceSession.defaultBuild(transport, scope, prefix, diagnostics)
    }
    private val direct = ConnectionProfile.Direct(LocalBroker("192.168.1.91"))

    @After
    fun tearDown() = parent.cancel()

    @Test
    fun `same profile returns the same source`() {
        val first: EntitySource = session.current(direct)
        assertSame(first, session.current(direct.copy()))
        assertTrue(scopes.single().isActive)
    }

    @Test
    fun `a new prefix cancels the old source and builds a new one`() {
        val first = session.current(direct)
        val second = session.current(direct.copy(prefix = "ha"))
        assertNotSame(first, second)
        assertEquals(listOf("homeassistant", "ha"), prefixes)
        assertFalse(scopes[0].isActive)
        assertTrue(scopes[1].isActive)
    }

    @Test
    fun `demo then direct with the same prefix builds a new source`() {
        val demo = session.current(ConnectionProfile.Demo)
        val real = session.current(direct)
        assertEquals(ConnectionProfile.Demo.prefix, direct.prefix)
        assertNotSame(demo, real)
        assertFalse(scopes[0].isActive)
        assertTrue(scopes[1].isActive)
    }

    @Test
    fun `reset cancels the source, clears its subscriptions, and the same profile then gets a fresh one`() = runBlocking {
        val first = session.current(ConnectionProfile.Demo)
        assertTrue(transport.subscriptions.isNotEmpty())
        session.reset()
        assertFalse(scopes[0].isActive)
        assertEquals(emptySet(), transport.subscriptions)
        val second = session.current(ConnectionProfile.Demo)
        assertNotSame(first, second)
        assertTrue(scopes[1].isActive)
    }
}
