package com.gekkotron.oikos.feature.energy.presentation

import com.gekkotron.oikos.feature.energy.FakeEntitySource
import com.gekkotron.oikos.feature.energy.energyEntity
import com.gekkotron.oikos.feature.energy.history.FakeHistoryClient
import com.gekkotron.oikos.feature.energy.numeric
import com.gekkotron.oikos.feature.energy.recorded
import com.gekkotron.oikos.history.HistoryResult
import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.UiOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class EnergyViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private var now = 0L
    private val source = FakeEntitySource().apply {
        entities.value = listOf(
            energyEntity("solar", EnergyRole.SOLAR),
            energyEntity("grid", EnergyRole.GRID),
            energyEntity("ac", EnergyRole.CONSUMER),
            energyEntity("heater", EnergyRole.CONSUMER),
        ).associateBy { it.uniqueId }
        stateMap.value = listOf(numeric("solar", 3200.0), numeric("grid", -400.0), numeric("ac", 600.0), numeric("heater", 1700.0)).associateBy { it.uniqueId }
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `state reflects the source`() = runTest(dispatcher) {
        val vm = EnergyViewModel(source, dispatcher) { now }
        backgroundScope.launch { vm.state.collect {} }
        assertEquals(2800.0, vm.state.value.node("house")!!.value)
        assertEquals(87, vm.state.value.selfConsumption)
    }

    @Test
    fun `the consumer order is carried between updates and throttled`() = runTest(dispatcher) {
        val vm = EnergyViewModel(source, dispatcher) { now }
        backgroundScope.launch { vm.state.collect {} }
        assertEquals(listOf("consumer:heater", "consumer:ac"), vm.state.value.consumers.map { it.id })
        now = 1_000
        source.stateMap.value += "ac" to numeric("ac", 2500.0)
        assertEquals(listOf("consumer:heater", "consumer:ac"), vm.state.value.consumers.map { it.id })
        assertEquals(2500.0, vm.state.value.node("consumer:ac")!!.value)
        now = 6_000
        source.stateMap.value += "heater" to numeric("heater", 1600.0)
        assertEquals(listOf("consumer:ac", "consumer:heater"), vm.state.value.consumers.map { it.id })
    }

    @Test
    fun `save writes the merged override`() = runTest(dispatcher) {
        val vm = EnergyViewModel(source, dispatcher) { now }
        vm.save("heater", SensorEdit("Water heater", EnergyRole.CONSUMER, invert = false))
        assertEquals(UiOverride(name = "Water heater", energyRole = EnergyRole.CONSUMER), source.overrides["heater"])
    }

    @Test
    fun `a failed save sends an event`() = runTest(dispatcher) {
        val vm = EnergyViewModel(source, dispatcher) { now }
        source.failWrites = true
        val events = mutableListOf<EnergyEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        vm.save("heater", SensorEdit("Water heater", EnergyRole.CONSUMER, invert = false))
        assertEquals(listOf<EnergyEvent>(EnergyEvent.SaveFailed), events)
    }

    /** Solar recorded on its own topic, without a meter: today is its power integral. */
    private val recordedSource = FakeEntitySource().apply {
        entities.value = listOf(energyEntity("solar", EnergyRole.SOLAR).recorded("power")).associateBy { it.uniqueId }
        stateMap.value = mapOf("solar" to numeric("solar", 3200.0))
    }

    @Test
    fun `today's values reach the nodes`() = runTest(dispatcher) {
        val history = FakeHistoryClient().apply { today = { _, _ -> HistoryResult.Ok(5.0) } }
        val vm = EnergyViewModel(recordedSource, dispatcher, history) { now }
        backgroundScope.launch { vm.state.collect {} }
        vm.state.first { it.node("solar")?.todayKwh == 5.0 }
        assertTrue(vm.state.value.historyAvailable)
    }

    @Test
    fun `no history, no today call`() = runTest(dispatcher) {
        val history = FakeHistoryClient().apply { available.value = false }
        val vm = EnergyViewModel(recordedSource, dispatcher, history) { now }
        backgroundScope.launch { vm.state.collect {} }
        assertEquals(3200.0, vm.state.value.node("solar")!!.value)
        assertEquals(emptyList(), history.calls)
        assertEquals(false, vm.state.value.historyAvailable)
    }

    @Test
    fun `today is fetched again every five minutes`() = runTest(dispatcher) {
        val history = FakeHistoryClient().apply { today = { _, _ -> HistoryResult.Ok(5.0) } }
        val vm = EnergyViewModel(recordedSource, StandardTestDispatcher(testScheduler), history) { now }
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertEquals(listOf("today solar POWER"), history.calls)
        advanceTimeBy(4.minutes)
        runCurrent()
        assertEquals(1, history.calls.size)
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(listOf("today solar POWER", "today solar POWER"), history.calls)
    }
}
