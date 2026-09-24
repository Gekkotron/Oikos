package com.gekkotron.oikos.state

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandFailure
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue.OnOff
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CommandTrackerTest {
    private val sw = testEntity("sw", Kind.SWITCH, "switch", """{"state_topic":"s","command_topic":"c"}""")

    private class Harness(val store: StateStore, val tracker: CommandTracker, val failures: MutableList<CommandFailure>)

    private fun TestScope.harness(): Harness {
        val store = StateStore().apply {
            setEntities(listOf(sw))
            onMessage("s", "OFF")
        }
        val tracker = CommandTracker(store, backgroundScope)
        val failures = mutableListOf<CommandFailure>()
        backgroundScope.launch { tracker.failures.collect { failures += it } }
        runCurrent()
        return Harness(store, tracker, failures)
    }

    private fun Harness.state() = store.states.value.getValue("sw")

    @Test
    fun `confirmation within the timeout clears pending`() = runTest {
        val h = harness()
        h.tracker.track("sw", Command.TurnOn(), OnOff(true), hasFeedback = true)
        assertTrue(h.state().pending)
        advanceTimeBy(4_000)
        h.store.onMessage("s", "ON")
        advanceTimeBy(2_000)
        runCurrent()
        assertFalse(h.state().pending)
        assertEquals(OnOff(true), h.state().value)
        assertEquals(emptyList(), h.failures)
    }

    @Test
    fun `no confirmation reverts after 5 s and reports`() = runTest {
        val h = harness()
        h.tracker.track("sw", Command.TurnOn(), OnOff(true), hasFeedback = true)
        advanceTimeBy(4_999)
        runCurrent()
        assertTrue(h.state().pending)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(h.state().pending)
        assertEquals(OnOff(false), h.state().value)
        assertEquals(listOf(CommandFailure("sw", Command.TurnOn(), "Not confirmed")), h.failures)
    }

    @Test
    fun `a new command restarts the timer`() = runTest {
        val h = harness()
        h.tracker.track("sw", Command.TurnOn(), OnOff(true), hasFeedback = true)
        advanceTimeBy(3_000)
        h.tracker.track("sw", Command.Toggle, OnOff(true), hasFeedback = true)
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(emptyList(), h.failures)
        assertTrue(h.state().pending)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(1, h.failures.size)
        assertEquals(Command.Toggle, h.failures.single().command)
    }

    @Test
    fun `without feedback the caller confirms and nothing times out`() = runTest {
        val h = harness()
        h.tracker.track("sw", Command.TurnOn(), OnOff(true), hasFeedback = false)
        h.store.confirmPending("sw")
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(h.state().pending)
        assertEquals(OnOff(true), h.state().value)
        assertEquals(emptyList(), h.failures)
    }

    @Test
    fun `null prediction tracks nothing`() = runTest {
        val h = harness()
        h.tracker.track("sw", Command.Press, null, hasFeedback = true)
        assertFalse(h.state().pending)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(emptyList(), h.failures)
    }

    @Test
    fun `abort reverts silently`() = runTest {
        val h = harness()
        h.tracker.track("sw", Command.TurnOn(), OnOff(true), hasFeedback = true)
        h.tracker.abort("sw")
        assertFalse(h.state().pending)
        assertEquals(OnOff(false), h.state().value)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(emptyList(), h.failures)
    }

    // Review Focus 5
    @Test
    fun `entity removed while pending emits nothing`() = runTest {
        val h = harness()
        h.tracker.track("sw", Command.TurnOn(), OnOff(true), hasFeedback = true)
        h.store.setEntities(emptyList())
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(emptyList(), h.failures)
        assertEquals(emptyMap(), h.store.states.value)
    }
}
