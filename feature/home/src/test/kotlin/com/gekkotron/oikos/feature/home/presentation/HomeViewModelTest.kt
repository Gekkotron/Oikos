package com.gekkotron.oikos.feature.home.presentation

import androidx.lifecycle.SavedStateHandle
import com.gekkotron.oikos.feature.home.FakeEntitySource
import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandFailure
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
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
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val source = FakeEntitySource().apply {
        entities.value = mapOf(
            "lamp" to testEntity("lamp", Kind.LIGHT, area = "kitchen"),
            "shutter" to testEntity("shutter", Kind.COVER, area = "kitchen"),
        )
        areas.value = listOf(Area("kitchen", "Kitchen"))
        stateMap.value = mapOf("lamp" to EntityState("lamp", StateValue.Light(true, 255)))
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `state reflects the source`() = runTest(dispatcher) {
        val vm = HomeViewModel(source, SavedStateHandle())
        backgroundScope.launch { vm.state.collect {} }
        assertFalse(vm.state.value.loading)
        assertEquals(setOf("lamp", "shutter"), vm.state.value.cards.map { it.id }.toSet())
    }

    @Test
    fun `selected room survives recreation`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        HomeViewModel(source, handle).select(RoomFilter.Area("kitchen"))
        val recreated = HomeViewModel(source, handle)
        backgroundScope.launch { recreated.state.collect {} }
        assertEquals(RoomFilter.Area("kitchen"), recreated.state.value.selected)
    }

    @Test
    fun `tapping sends an absolute on-off so Retry repeats the intent, and ignores sheet cards`() = runTest(dispatcher) {
        source.entities.value += "off" to testEntity("off", Kind.SWITCH, area = "kitchen")
        val vm = HomeViewModel(source, SavedStateHandle())
        backgroundScope.launch { vm.state.collect {} }
        val cards = vm.state.value.cards.associateBy { it.id }
        vm.tap(cards.getValue("lamp"))
        vm.tap(cards.getValue("off"))
        vm.tap(cards.getValue("shutter"))
        assertEquals(listOf("lamp" to Command.TurnOff, "off" to Command.TurnOn()), source.sent)
    }

    @Test
    fun `unconfirmed command becomes a NotConfirmed event`() = runTest(dispatcher) {
        val vm = HomeViewModel(source, SavedStateHandle())
        source.commandFailures.emit(CommandFailure("lamp", Command.Toggle, "timeout"))
        assertEquals(HomeEvent.NotConfirmed("lamp", Command.Toggle), vm.events.first())
    }

    @Test
    fun `rejected command becomes a Rejected event`() = runTest(dispatcher) {
        source.result = CommandResult.Rejected("unavailable")
        val vm = HomeViewModel(source, SavedStateHandle())
        vm.send("lamp", Command.TurnOff)
        assertEquals(HomeEvent.Rejected("lamp", "unavailable"), vm.events.first())
    }
}
