package com.gekkotron.oikos.transport

import com.gekkotron.oikos.transport.testing.FakeManagedTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveTransportTest {
    private val created = mutableListOf<FakeManagedTransport>()
    private val direct = TransportSpec.Direct("broker.example", 1883, tls = false, ws = false, username = null, password = null, clientId = "oikos-test")

    private fun TestScope.active(
        specs: Flow<TransportSpec?>,
        seed: FakeManagedTransport.() -> Unit = {},
    ) = ActiveTransport(specs, { FakeManagedTransport(it).apply(seed).also(created::add) }, backgroundScope)

    @Test
    fun `no spec is offline and not configured`() = runTest {
        val t = active(flowOf(null))
        runCurrent()
        assertEquals(LinkState.Offline(LinkState.Offline.NOT_CONFIGURED), t.link.value)
        assertTrue(created.isEmpty())
    }

    @Test
    fun `a spec starts its transport and mirrors its link`() = runTest {
        val t = active(flowOf(TransportSpec.Demo))
        runCurrent()
        assertEquals(1, created.size)
        assertTrue(created[0].started)
        created[0].fake.setLink(LinkState.Reconnecting(2))
        runCurrent()
        assertEquals(LinkState.Reconnecting(2), t.link.value)
    }

    @Test
    fun `switching spec closes the old transport and replays subscriptions`() = runTest {
        val specs = MutableStateFlow<TransportSpec?>(TransportSpec.Demo)
        val t = active(specs)
        runCurrent()
        t.setSubscriptions(setOf("homeassistant/#"))
        specs.value = direct
        runCurrent()
        assertTrue(created[0].closed)
        assertEquals(direct, created[1].spec)
        assertEquals(setOf("homeassistant/#"), created[1].fake.subscriptions)
    }

    @Test
    fun `clearing the spec closes the transport`() = runTest {
        val specs = MutableStateFlow<TransportSpec?>(direct)
        val t = active(specs)
        runCurrent()
        specs.value = null
        runCurrent()
        assertTrue(created.single().closed)
        assertEquals(LinkState.Offline(LinkState.Offline.NOT_CONFIGURED), t.link.value)
    }

    @Test
    fun `messages come only from the current transport`() = runTest {
        val specs = MutableStateFlow<TransportSpec?>(TransportSpec.Demo)
        val t = active(specs)
        val received = mutableListOf<String>()
        backgroundScope.launch { t.messages().collect { received += it.topic } }
        t.setSubscriptions(setOf("#"))
        runCurrent()
        val first = created[0]
        specs.value = direct
        runCurrent()
        first.fake.deliver("old/topic", "x")
        created[1].fake.deliver("new/topic", "y")
        runCurrent()
        assertEquals(listOf("new/topic"), received)
    }

    @Test
    fun `retained messages replayed on start reach collectors`() = runTest {
        val specs = MutableStateFlow<TransportSpec?>(null)
        val t = active(specs) { fake.retain("homeassistant/light/a/config", "{}") }
        val received = mutableListOf<MqttMessage>()
        backgroundScope.launch { t.messages().collect { received += it } }
        t.setSubscriptions(setOf("homeassistant/#"))
        specs.value = TransportSpec.Demo
        runCurrent()
        assertEquals(listOf("homeassistant/light/a/config"), received.map { it.topic })
        assertTrue(received.single().retained)
    }

    @Test
    fun `publish without a transport fails`() = runTest {
        val t = active(flowOf(null))
        runCurrent()
        assertFailsWith<PublishFailed> { t.publish("a/b", byteArrayOf(1)) }
    }

    @Test
    fun `publish goes to the current transport`() = runTest {
        val t = active(flowOf(direct))
        runCurrent()
        t.publish("lamp/set", "ON".encodeToByteArray())
        assertEquals(listOf("lamp/set" to "ON"), created.single().fake.publishedStrings())
    }

    @Test
    fun `background closes after the grace period and foreground restarts`() = runTest {
        val t = active(flowOf(TransportSpec.Demo))
        runCurrent()
        t.setForeground(false)
        advanceTimeBy(29.seconds)
        runCurrent()
        assertFalse(created[0].closed)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertTrue(created[0].closed)
        assertEquals(LinkState.Offline(LinkState.Offline.PAUSED), t.link.value)
        t.setForeground(true)
        runCurrent()
        assertEquals(2, created.size)
        assertTrue(created[1].started)
    }

    @Test
    fun `returning within the grace period keeps the transport`() = runTest {
        active(flowOf(TransportSpec.Demo)).also { runCurrent() }.apply {
            setForeground(false)
            advanceTimeBy(10.seconds)
            setForeground(true)
            advanceTimeBy(60.seconds)
            runCurrent()
        }
        assertFalse(created.single().closed)
    }

    @Test
    fun `a transport whose close throws does not block the next transport`() = runTest {
        val specs = MutableStateFlow<TransportSpec?>(TransportSpec.Demo)
        val t = ActiveTransport(
            specs,
            { spec -> FakeManagedTransport(spec, failClose = created.isEmpty()).also(created::add) },
            backgroundScope,
        )
        runCurrent()
        specs.value = direct
        runCurrent()
        assertTrue(created[0].closed)
        assertEquals(direct, created[1].spec)
        assertTrue(created[1].started)
        assertEquals(t.link.value, created[1].fake.link.value)
    }

    @Test
    fun `a transport whose start throws surfaces offline and a later spec still starts`() = runTest {
        val specs = MutableStateFlow<TransportSpec?>(TransportSpec.Demo)
        val t = ActiveTransport(
            specs,
            { spec -> FakeManagedTransport(spec, failStart = created.isEmpty()).also(created::add) },
            backgroundScope,
        )
        runCurrent()
        assertTrue(created[0].started)
        assertEquals(LinkState.Offline(LinkState.Offline.FAILED), t.link.value)

        specs.value = direct
        runCurrent()
        assertEquals(direct, created[1].spec)
        assertTrue(created[1].started)
        assertEquals(t.link.value, created[1].fake.link.value)
    }

    /** Records how many collectors are still attached to it when [close] runs. */
    private class ProbeTransport : ManagedTransport {
        private val messageFlow = MutableSharedFlow<MqttMessage>()
        private val linkFlow = MutableStateFlow<LinkState>(LinkState.Connecting)
        override val link: StateFlow<LinkState> = linkFlow
        var collectorsAtClose = -1
            private set

        override fun messages(): SharedFlow<MqttMessage> = messageFlow
        override fun start() = Unit
        override suspend fun setSubscriptions(filters: Set<String>) = Unit
        override suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean) = Unit
        override suspend fun close() {
            collectorsAtClose = messageFlow.subscriptionCount.value + linkFlow.subscriptionCount.value
        }
    }

    @Test
    fun `the old transport's collectors have finished before it is closed`() = runTest {
        val specs = MutableStateFlow<TransportSpec?>(TransportSpec.Demo)
        val probes = mutableListOf<ProbeTransport>()
        ActiveTransport(specs, { ProbeTransport().also(probes::add) }, backgroundScope)
        runCurrent()
        specs.value = direct
        runCurrent()
        assertEquals(2, probes.size)
        assertEquals(0, probes[0].collectorsAtClose)
    }
}
