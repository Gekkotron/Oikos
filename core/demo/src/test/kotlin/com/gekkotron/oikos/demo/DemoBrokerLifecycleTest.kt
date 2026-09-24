package com.gekkotron.oikos.demo

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DemoBrokerLifecycleTest {
    @Test
    fun `start ticks live sensors and close stops them`() = runTest {
        val broker = DemoBroker(backgroundScope)
        val received = mutableListOf<String>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { broker.messages().collect { received += it.topic } }
        broker.setSubscriptions(setOf("#"))
        runCurrent()
        received.clear() // drop the retained replay; only ticks count from here

        broker.start()
        advanceTimeBy(3.5.seconds)
        runCurrent()
        val afterStart = received.size
        assertTrue(afterStart > 0, "expected sensor ticks after start()")

        broker.close()
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(afterStart, received.size)
    }
}
