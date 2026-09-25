package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.EntityCache
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.testing.FakeMqttTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    private val lightTopic = "homeassistant/light/0x01/light/config"
    private val lightConfig = """{"schema":"json","command_topic":"z2m/lampe/set","state_topic":"z2m/lampe","unique_id":"lamp","name":"Lampe"}"""

    @Test
    fun `a direct source is restored from the cache for its key`() = runBlocking {
        val cache = FakeEntityCache(direct.cacheKey() to mapOf(lightTopic to lightConfig))
        transport.setLink(LinkState.Offline("test"))
        val source = EntitySourceSession(transport, parent, cache = cache).current(direct)
        withTimeout(2_000) { source.entities.first { "lamp" in it } }
        Unit
    }

    @Test
    fun `a cache for another broker is not restored`() = runBlocking {
        val cache = FakeEntityCache("other:1883:homeassistant" to mapOf(lightTopic to lightConfig))
        transport.setLink(LinkState.Offline("test"))
        val source = EntitySourceSession(transport, parent, cache = cache).current(direct)
        delay(200)
        assertTrue(source.entities.value.isEmpty())
    }

    @Test
    fun `the journal is saved under the profile key`() = runBlocking {
        val cache = FakeEntityCache()
        EntitySourceSession(transport, parent, cache = cache).current(direct)
        transport.deliver(lightTopic, lightConfig, retain = true)
        withTimeout(5_000) { while (cache.stored == null) delay(50) }
        assertEquals(direct.cacheKey(), cache.stored!!.first)
        assertTrue(lightTopic in cache.stored!!.second)
    }

    @Test
    fun `the demo and reset clear the cache`() = runBlocking {
        val cache = FakeEntityCache(direct.cacheKey() to mapOf(lightTopic to lightConfig))
        val session = EntitySourceSession(transport, parent, cache = cache)
        session.current(ConnectionProfile.Demo)
        withTimeout(2_000) { while (cache.clears == 0) delay(10) }
        session.current(direct)
        session.reset()
        assertEquals(2, cache.clears)
    }
}

private class FakeEntityCache(var stored: Pair<String, Map<String, String>>? = null) : EntityCache {
    var clears = 0

    override suspend fun load(key: String) = stored?.takeIf { it.first == key }?.second.orEmpty()

    override suspend fun save(key: String, messages: Map<String, String>) {
        stored = key to messages
    }

    override suspend fun clear() {
        clears++
        stored = null
    }
}
