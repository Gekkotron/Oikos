package com.gekkotron.oikos.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

class EntityCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val file get() = tmp.root.resolve("entity-cache.json")
    private fun TestScope.cache() = FileEntityCache.create(file, backgroundScope)
    private val key = "192.168.1.91:1883:homeassistant"
    private val messages = mapOf("homeassistant/light/a/config" to """{"unique_id":"a"}""", "z2m/a" to """{"state":"ON"}""")

    @Test
    fun `missing file loads empty`() = runTest {
        assertEquals(emptyMap(), cache().load(key))
    }

    @Test
    fun `round trip for the same key`() = runTest {
        val c = cache()
        c.save(key, messages)
        assertEquals(messages, c.load(key))
    }

    @Test
    fun `a new instance reads what an earlier one saved`() = runTest {
        val job = Job()
        FileEntityCache.create(file, CoroutineScope(coroutineContext + job)).save(key, messages)
        job.cancelAndJoin()
        assertEquals(messages, cache().load(key))
    }

    @Test
    fun `another key loads empty`() = runTest {
        val c = cache()
        c.save(key, messages)
        assertEquals(emptyMap(), c.load("other:1883:homeassistant"))
    }

    @Test
    fun `clear forgets everything`() = runTest {
        val c = cache()
        c.save(key, messages)
        c.clear()
        assertEquals(emptyMap(), c.load(key))
    }

    @Test
    fun `a corrupt file loads empty`() = runTest {
        file.writeText("{ not json")
        assertEquals(emptyMap(), cache().load(key))
    }
}
