package com.gekkotron.oikos.push

import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        store.save("tok", "oikos-push/tokens/a", Instant.ofEpochSecond(1_790_000_000))
        assertEquals(LastRegistration("tok", "oikos-push/tokens/a", Instant.ofEpochSecond(1_790_000_000)), store.last())
        store.clear()
        assertNull(store.last())
    }

    @Test
    fun `a corrupt file reads as not registered`(): Unit = runTest {
        val file = tmp.root.resolve("r.json").apply { writeText("{nope") }
        assertNull(RegistrationStore.create(file, backgroundScope).last())
    }
}
