package com.gekkotron.oikos.connection

import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.transport.testing.FakeMqttTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import org.junit.After
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EntitySourceSessionTest {
    private val parent = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val scopes = mutableListOf<CoroutineScope>()
    private val session = EntitySourceSession(FakeMqttTransport(), parent) { transport, scope, prefix, diagnostics ->
        scopes += scope
        EntitySourceSession.defaultBuild(transport, scope, prefix, diagnostics)
    }

    @After
    fun tearDown() = parent.cancel()

    @Test
    fun `same prefix returns the same source`() {
        val first: EntitySource = session.current("homeassistant")
        assertSame(first, session.current("homeassistant"))
        assertTrue(scopes.single().isActive)
    }

    @Test
    fun `a new prefix cancels the old source and builds a new one`() {
        val first = session.current("homeassistant")
        val second = session.current("ha")
        assertNotSame(first, second)
        assertFalse(scopes[0].isActive)
        assertTrue(scopes[1].isActive)
    }
}
