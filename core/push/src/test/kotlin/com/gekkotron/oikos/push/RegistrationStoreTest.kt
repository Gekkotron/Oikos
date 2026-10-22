package com.gekkotron.oikos.push

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RegistrationStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `nothing is registered at first`(): Unit = runTest {
        assertNull(RegistrationStore.create(tmp.root.resolve("r.json"), backgroundScope).last())
    }

    @Test
    fun `a registration round-trips and clears`(): Unit = runTest {
        val store = RegistrationStore.create(tmp.root.resolve("r.json"), backgroundScope)
        store.save("tok", "oikos/push/tokens/a", Instant.ofEpochSecond(1_790_000_000))
        assertEquals(LastRegistration("tok", "oikos/push/tokens/a", Instant.ofEpochSecond(1_790_000_000)), store.last())
        store.clear()
        assertNull(store.last())
    }

    @Test
    fun `a corrupt file reads as not registered`(): Unit = runTest {
        val file = tmp.root.resolve("r.json").apply { writeText("{nope") }
        assertNull(RegistrationStore.create(file, backgroundScope).last())
    }

    private val saved = LastRegistration("tok", "oikos/push/tokens/a", Instant.ofEpochSecond(1_790_000_000))

    @Test
    fun `a subscriber after a save sees the saved registration`(): Unit = runTest {
        val store = RegistrationStore.create(tmp.root.resolve("r.json"), backgroundScope)
        store.save(saved.token, saved.topic, saved.at)
        assertEquals(saved, withContext(Dispatchers.Default) { withTimeout(5.seconds) { store.observe().first { it != null } } })
    }

    @Test
    fun `a subscriber before a save sees it`(): Unit = runTest {
        val store = RegistrationStore.create(tmp.root.resolve("r.json"), backgroundScope)
        val seen = async(Dispatchers.Default) { withTimeout(5.seconds) { store.observe().first { it != null } } }
        store.save(saved.token, saved.topic, saved.at)
        assertEquals(saved, seen.await())
    }

    @Test
    fun `legacy cleared is false until it is marked, and a save preserves it`(): Unit = runTest {
        val store = RegistrationStore.create(tmp.root.resolve("r.json"), backgroundScope)
        store.save("tok", "oikos/push/tokens/a", Instant.ofEpochSecond(1_790_000_000))
        assertFalse(store.legacyCleared())
        store.markLegacyCleared()
        assertTrue(store.legacyCleared())
        // A later save (new token or topic) keeps the flag so the clear does not re-run.
        store.save("tok2", "oikos/push/tokens/a", Instant.ofEpochSecond(1_790_000_100))
        assertTrue(store.legacyCleared())
        assertEquals(true, store.last()?.legacyCleared)
        // Set-up-again wipes everything, including the flag.
        store.clear()
        assertFalse(store.legacyCleared())
    }
}
