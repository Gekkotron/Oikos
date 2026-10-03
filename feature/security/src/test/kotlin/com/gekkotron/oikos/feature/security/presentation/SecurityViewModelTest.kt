package com.gekkotron.oikos.feature.security.presentation

import com.gekkotron.oikos.feature.security.action
import com.gekkotron.oikos.feature.security.FakeEntitySource
import com.gekkotron.oikos.feature.security.alarm
import com.gekkotron.oikos.feature.security.on
import com.gekkotron.oikos.feature.security.panel
import com.gekkotron.oikos.feature.security.sensor
import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandFailure
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.UiOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SecurityViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val source = FakeEntitySource().apply {
        entities.value = listOf(panel("alarm"), sensor("door", "door", ui = UiOverride(favorite = true))).associateBy { it.uniqueId }
        stateMap.value = listOf(alarm("alarm", AlarmState.DISARMED), on("door", true)).associateBy { it.uniqueId }
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `state reflects the source`() = runTest(dispatcher) {
        val vm = SecurityViewModel(source, dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        assertEquals(AlarmState.DISARMED, vm.state.value.panels.single().state)
        assertEquals(listOf("door"), vm.state.value.sensors.map { it.id })
    }

    @Test
    fun `send forwards the command and a rejection becomes an event`() = runTest(dispatcher) {
        val vm = SecurityViewModel(source, dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        val events = mutableListOf<SecurityEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        source.result = CommandResult.Rejected("wrong code")
        vm.send("alarm", Command.Disarm("1"))
        assertEquals(listOf<Pair<String, Command>>("alarm" to Command.Disarm("1")), source.sent)
        assertEquals(listOf<SecurityEvent>(SecurityEvent.Rejected("wrong code")), events)
    }

    @Test
    fun `a stale panel waits for the connection`() = runTest(dispatcher) {
        source.stateMap.value += "alarm" to alarm("alarm", AlarmState.DISARMED, stale = true)
        val vm = SecurityViewModel(source, dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        val events = mutableListOf<SecurityEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        vm.send("alarm", Command.Arm(AlarmMode.AWAY))
        assertTrue(source.sent.isEmpty())
        assertEquals(listOf<SecurityEvent>(SecurityEvent.WaitingForConnection), events)
    }

    @Test
    fun `only failures of commands sent here are reported`() = runTest(dispatcher) {
        val vm = SecurityViewModel(source, dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        val events = mutableListOf<SecurityEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        source.commandFailures.emit(CommandFailure("lamp", Command.TurnOff, "timeout"))
        vm.send("alarm", Command.Arm(AlarmMode.AWAY))
        source.commandFailures.emit(CommandFailure("alarm", Command.Arm(AlarmMode.AWAY), "timeout"))
        assertEquals(listOf<SecurityEvent>(SecurityEvent.NotConfirmed("alarm", Command.Arm(AlarmMode.AWAY))), events)
    }

    @Test
    fun `hiding keeps the rest of the override and a failed write is an event`() = runTest(dispatcher) {
        val vm = SecurityViewModel(source, dispatcher)
        val events = mutableListOf<SecurityEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        vm.setSecurityHidden("door", true)
        assertEquals(UiOverride(favorite = true, securityHidden = true), source.overrides["door"])
        source.failWrites = true
        vm.setSecurityHidden("door", false)
        assertEquals(listOf<SecurityEvent>(SecurityEvent.SaveFailed), events)
    }

    @Test
    fun `a stale action waits for the connection`() = runTest(dispatcher) {
        source.entities.value += "siren" to action("siren")
        source.stateMap.value += "siren" to on("siren", false, stale = true)
        val vm = SecurityViewModel(source, dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        val events = mutableListOf<SecurityEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        vm.send("siren", Command.TurnOn())
        assertTrue(source.sent.isEmpty())
        assertEquals(listOf<SecurityEvent>(SecurityEvent.WaitingForConnection), events)
    }

    @Test
    fun `setAction saves both flags, keeps the rest, and a failed write is an event`() = runTest(dispatcher) {
        source.entities.value += "siren" to action("siren", ui = UiOverride(favorite = true, securityAction = true))
        val vm = SecurityViewModel(source, dispatcher)
        val events = mutableListOf<SecurityEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        vm.setAction("siren", show = true, ask = false)
        assertEquals(UiOverride(favorite = true, securityAction = true, securityAsk = false), source.overrides["siren"])
        source.failWrites = true
        vm.setAction("siren", show = false, ask = false)
        assertEquals(listOf<SecurityEvent>(SecurityEvent.SaveFailed), events)
    }
}
