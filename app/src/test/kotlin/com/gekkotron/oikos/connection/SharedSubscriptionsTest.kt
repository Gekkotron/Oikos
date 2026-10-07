package com.gekkotron.oikos.connection

import com.gekkotron.oikos.transport.testing.FakeMqttTransport
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class SharedSubscriptionsTest {
    private val fake = FakeMqttTransport()
    private val shared = SharedSubscriptions(fake)

    @Test
    fun `the source's filters and the extra ones are subscribed together`(): Unit = runTest {
        shared.setSubscriptions(setOf("homeassistant/#"))
        shared.setExtra(setOf("oikos-push/history"))
        assertEquals(setOf("homeassistant/#", "oikos-push/history"), fake.subscriptions)
    }

    @Test
    fun `the source replacing its filters keeps the extra ones`(): Unit = runTest {
        shared.setExtra(setOf("oikos-push/history"))
        shared.setSubscriptions(setOf("homeassistant/#", "z2m/#"))
        shared.setSubscriptions(emptySet())
        assertEquals(setOf("oikos-push/history"), fake.subscriptions)
    }

    @Test
    fun `clearing the extra ones leaves the source's`(): Unit = runTest {
        shared.setSubscriptions(setOf("homeassistant/#"))
        shared.setExtra(setOf("oikos-push/history"))
        shared.setExtra(emptySet())
        assertEquals(setOf("homeassistant/#"), fake.subscriptions)
    }

    @Test
    fun `publish and messages pass through`(): Unit = runTest {
        shared.publish("a/b", "x".encodeToByteArray(), 1, false)
        assertEquals(listOf("a/b" to "x"), fake.publishedStrings())
    }
}
