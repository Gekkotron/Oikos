package com.gekkotron.oikos.transport

import com.gekkotron.oikos.transport.testing.FakeMqttTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class FakeMqttTransportTest {
    @Test
    fun `retained messages replay only to newly added filters`() = runTest {
        val t = FakeMqttTransport()
        t.deliver("a/1", "x", retain = true)
        t.deliver("b/1", "y", retain = true)
        val got = mutableListOf<MqttMessage>()
        backgroundScope.launch { t.messages().collect { got += it } }
        runCurrent()

        t.setSubscriptions(setOf("a/#"))
        t.setSubscriptions(setOf("a/#", "b/#"))
        runCurrent()

        assertEquals(listOf("a/1" to "x", "b/1" to "y"), got.map { it.topic to it.payloadString() })
        assertEquals(true, got.all { it.retained })
    }

    @Test
    fun `live messages reach matching subscriptions only`() = runTest {
        val t = FakeMqttTransport()
        val got = mutableListOf<String>()
        backgroundScope.launch { t.messages().collect { got += it.topic } }
        runCurrent()
        t.setSubscriptions(setOf("a/+"))
        t.deliver("a/1", "x")
        t.deliver("c/1", "x")
        runCurrent()
        assertEquals(listOf("a/1"), got)
    }

    @Test
    fun `publish is recorded, echoed, and empty retained clears`() = runTest {
        val t = FakeMqttTransport()
        t.setSubscriptions(setOf("oikos/ui/#"))
        t.publish("oikos/ui/x", "{}".encodeToByteArray(), retain = true)
        assertEquals(listOf("oikos/ui/x" to "{}"), t.publishedStrings())

        t.publish("oikos/ui/x", ByteArray(0), retain = true)
        val got = mutableListOf<String>()
        backgroundScope.launch { t.messages().collect { got += it.topic } }
        runCurrent()
        t.setSubscriptions(emptySet())
        t.setSubscriptions(setOf("oikos/ui/#"))
        runCurrent()
        assertEquals(emptyList(), got)
    }

    @Test
    fun `failPublishes throws PublishFailed`() = runTest {
        val t = FakeMqttTransport().apply { failPublishes = true }
        kotlin.test.assertFailsWith<PublishFailed> { t.publish("a", ByteArray(0)) }
    }

    @Test
    fun `message equality compares payload content`() {
        assertEquals(MqttMessage("a", byteArrayOf(1), false), MqttMessage("a", byteArrayOf(1), false))
    }
}
