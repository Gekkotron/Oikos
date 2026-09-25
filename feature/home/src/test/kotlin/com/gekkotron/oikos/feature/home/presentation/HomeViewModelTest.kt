package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.model.UiOverride
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
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        assertFalse(vm.state.value.loading)
        assertEquals(setOf("lamp", "shutter"), vm.state.value.cards.map { it.id }.toSet())
    }

    @Test
    fun `selected room survives recreation`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        HomeViewModel(source, handle, dispatcher).select(RoomFilter.Area("kitchen"))
        val recreated = HomeViewModel(source, handle, dispatcher)
        backgroundScope.launch { recreated.state.collect {} }
        assertEquals(RoomFilter.Area("kitchen"), recreated.state.value.selected)
    }

    @Test
    fun `a stale Hidden selection resets to All instead of reappearing once something else is hidden`() = runTest(dispatcher) {
        source.entities.value += "secret" to testEntity("secret", Kind.SWITCH, area = "kitchen", ui = UiOverride(hidden = true))
        val handle = SavedStateHandle()
        val vm = HomeViewModel(source, handle, dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        vm.select(RoomFilter.Hidden)
        assertEquals(RoomFilter.Hidden, vm.state.value.selected)
        // Unhiding the last hidden card drops the Hidden chip; the view falls back to All.
        source.entities.value += "secret" to testEntity("secret", Kind.SWITCH, area = "kitchen")
        assertEquals(RoomFilter.All, vm.state.value.selected)
        // Hiding another card brings the Hidden chip back; a stale saved key would jump back to it.
        source.entities.value += "lamp" to testEntity("lamp", Kind.LIGHT, area = "kitchen", ui = UiOverride(hidden = true))
        assertEquals(RoomFilter.All, vm.state.value.selected)
    }

    @Test
    fun `tapping sends an absolute on-off so Retry repeats the intent, and ignores sheet cards`() = runTest(dispatcher) {
        source.entities.value += "off" to testEntity("off", Kind.SWITCH, area = "kitchen")
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        val cards = vm.state.value.cards.associateBy { it.id }
        vm.tap(cards.getValue("lamp"))
        vm.tap(cards.getValue("off"))
        vm.tap(cards.getValue("shutter"))
        assertEquals(listOf("lamp" to Command.TurnOff, "off" to Command.TurnOn()), source.sent)
    }

    @Test
    fun `unconfirmed command becomes a NotConfirmed event`() = runTest(dispatcher) {
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher)
        source.commandFailures.emit(CommandFailure("lamp", Command.Toggle, "timeout"))
        assertEquals(HomeEvent.NotConfirmed("lamp", Command.Toggle), vm.events.first())
    }

    @Test
    fun `rejected command becomes a Rejected event`() = runTest(dispatcher) {
        source.result = CommandResult.Rejected("unavailable")
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher)
        vm.send("lamp", Command.TurnOff)
        assertEquals(HomeEvent.Rejected("lamp", "unavailable"), vm.events.first())
    }

    @Test
    fun `a burst of state updates collapses, with the final state reflecting the last update`() = runTest(dispatcher) {
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        repeat(50) { i ->
            source.stateMap.value = mapOf("lamp" to EntityState("lamp", StateValue.Light(on = i % 2 == 0, brightness = i)))
        }
        val lamp = vm.state.value.cards.first { it.id == "lamp" }
        assertFalse(lamp.on)
    }

    @Test
    fun `customize writes the override`() = runTest(dispatcher) {
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher)
        vm.customize("lamp", emptyList(), UiOverride(name = "Ceiling"))
        assertEquals(UiOverride(name = "Ceiling"), source.overrides["lamp"])
        assertEquals(listOf("override"), source.calls)
    }

    @Test
    fun `customize failure emits SaveFailed`() = runTest(dispatcher) {
        source.failWrites = true
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher)
        vm.customize("lamp", emptyList(), UiOverride(hidden = true))
        assertEquals(HomeEvent.SaveFailed, vm.events.first())
    }

    @Test
    fun `customize with a new room publishes the areas, then the override, in order`() = runTest(dispatcher) {
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher)
        vm.customize("lamp", listOf(Area("office", "Office")), UiOverride(area = "office"))
        assertEquals(listOf("areas", "override"), source.calls)
        assertEquals(listOf(listOf(Area("kitchen", "Kitchen"), Area("office", "Office"))), source.areaWrites)
        assertEquals(UiOverride(area = "office"), source.overrides["lamp"])
    }

    @Test
    fun `when publishing the new room fails, the override is never sent`() = runTest(dispatcher) {
        source.failWrites = true
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher)
        vm.customize("lamp", listOf(Area("office", "Office")), UiOverride(area = "office"))
        assertEquals(listOf("areas"), source.calls)
        assertEquals(emptyMap(), source.overrides)
        assertEquals(HomeEvent.SaveFailed, vm.events.first())
    }
}
