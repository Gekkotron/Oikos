package com.gekkotron.oikos.feature.energy.presentation

import com.gekkotron.oikos.feature.energy.FakeEntitySource
import com.gekkotron.oikos.feature.energy.energyEntity
import com.gekkotron.oikos.feature.energy.numeric
import com.gekkotron.oikos.model.EnergyRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

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
}
