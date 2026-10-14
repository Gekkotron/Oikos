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
import com.gekkotron.oikos.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.CoverState
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher, preferences = testPreferences())
        backgroundScope.launch { vm.state.collect {} }
        assertFalse(vm.state.value.loading)
        assertEquals(setOf("lamp", "shutter"), vm.state.value.cards.map { it.id }.toSet())
    }

    @Test
    fun `selected room survives recreation`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        HomeViewModel(source, handle, dispatcher, preferences = testPreferences()).select(RoomFilter.Area("kitchen"))
        val recreated = HomeViewModel(source, handle, dispatcher, preferences = testPreferences())
        backgroundScope.launch { recreated.state.collect {} }
        assertEquals(RoomFilter.Area("kitchen"), recreated.state.value.selected)
    }

    @Test
    fun `a stale Hidden selection resets to All instead of reappearing once something else is hidden`() = runTest(dispatcher) {
        source.entities.value += "secret" to testEntity("secret", Kind.SWITCH, area = "kitchen", ui = UiOverride(hidden = true))
        val handle = SavedStateHandle()
        val vm = HomeViewModel(source, handle, dispatcher, preferences = testPreferences())
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
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher, preferences = testPreferences())
        backgroundScope.launch { vm.state.collect {} }
        val cards = vm.state.value.cards.associateBy { it.id }
        vm.tap(cards.getValue("lamp"))
        vm.tap(cards.getValue("off"))
        vm.tap(cards.getValue("shutter"))
        assertEquals(listOf("lamp" to Command.TurnOff, "off" to Command.TurnOn()), source.sent)
    }

    @Test
    fun `tapping a light with a saved on-preset sends that colour and brightness, not a bare on`() = runTest(dispatcher) {
        source.entities.value = mapOf(
            "led" to testEntity(
                "led", Kind.LIGHT, area = "kitchen",
                capabilities = Capabilities(brightness = true, colorModes = setOf("hs")),
                ui = UiOverride(lightOnPreset = "white"),
            ),
        )
        source.stateMap.value = mapOf("led" to EntityState("led", StateValue.Light(false, 10)))
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher, preferences = testPreferences())
        backgroundScope.launch { vm.state.collect {} }
        val led = vm.state.value.card("led")!!
        vm.tap(led)
        assertEquals("led" to Command.TurnOn(brightness = 255, color = com.gekkotron.oikos.model.Hs(0.0, 0.0)), source.sent.last())
    }

    @Test
    fun `a saved on-preset brightness overrides the preset's baked value`() = runTest(dispatcher) {
        source.entities.value = mapOf(
            "led" to testEntity(
                "led", Kind.LIGHT, area = "kitchen",
                capabilities = Capabilities(brightness = true, colorModes = setOf("hs")),
                ui = UiOverride(lightOnPreset = "white", lightOnBrightness = 80),
            ),
        )
        source.stateMap.value = mapOf("led" to EntityState("led", StateValue.Light(false, 10)))
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher, preferences = testPreferences())
        backgroundScope.launch { vm.state.collect {} }
        vm.tap(vm.state.value.card("led")!!)
        assertEquals("led" to Command.TurnOn(brightness = 80, color = com.gekkotron.oikos.model.Hs(0.0, 0.0)), source.sent.last())
    }

    @Test
    fun `unconfirmed command becomes a NotConfirmed event`() = runTest(dispatcher) {
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher, preferences = testPreferences())
        source.commandFailures.emit(CommandFailure("lamp", Command.Toggle, "timeout"))
        assertEquals(HomeEvent.NotConfirmed("lamp", Command.Toggle), vm.events.first())
    }

    @Test
    fun `rejected command becomes a Rejected event`() = runTest(dispatcher) {
        source.result = CommandResult.Rejected("unavailable")
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher, preferences = testPreferences())
        vm.send("lamp", Command.TurnOff)
        assertEquals(HomeEvent.Rejected("lamp", "unavailable"), vm.events.first())
    }

    @Test
    fun `a burst of state updates collapses, with the final state reflecting the last update`() = runTest(dispatcher) {
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher, preferences = testPreferences())
        backgroundScope.launch { vm.state.collect {} }
        repeat(50) { i ->
            source.stateMap.value = mapOf("lamp" to EntityState("lamp", StateValue.Light(on = i % 2 == 0, brightness = i)))
        }
        val lamp = vm.state.value.cards.first { it.id == "lamp" }
        assertFalse(lamp.on)
    }

    @Test
    fun `customize writes the override`() = runTest(dispatcher) {
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher, preferences = testPreferences())
        vm.customize("lamp", emptyList(), UiOverride(name = "Ceiling"))
        assertEquals(UiOverride(name = "Ceiling"), source.overrides["lamp"])
        assertEquals(listOf("override"), source.calls)
    }

    @Test
    fun `customize failure emits SaveFailed`() = runTest(dispatcher) {
        source.failWrites = true
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher, preferences = testPreferences())
        vm.customize("lamp", emptyList(), UiOverride(hidden = true))
        assertEquals(HomeEvent.SaveFailed, vm.events.first())
    }

    @Test
    fun `customize with a new room publishes the areas, then the override, in order`() = runTest(dispatcher) {
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher, preferences = testPreferences())
        vm.customize("lamp", listOf(Area("office", "Office")), UiOverride(area = "office"))
        assertEquals(listOf("areas", "override"), source.calls)
        assertEquals(listOf(listOf(Area("kitchen", "Kitchen"), Area("office", "Office"))), source.areaWrites)
        assertEquals(UiOverride(area = "office"), source.overrides["lamp"])
    }

    @Test
    fun `when publishing the new room fails, the override is never sent`() = runTest(dispatcher) {
        source.failWrites = true
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher, preferences = testPreferences())
        vm.customize("lamp", listOf(Area("office", "Office")), UiOverride(area = "office"))
        assertEquals(listOf("areas"), source.calls)
        assertEquals(emptyMap(), source.overrides)
        assertEquals(HomeEvent.SaveFailed, vm.events.first())
    }

    @Test
    fun `a room typed and then left for another is not published`() = runTest(dispatcher) {
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher, preferences = testPreferences())
        vm.customize("lamp", listOf(Area("office", "Office"), Area("attic", "Attic")), UiOverride(area = "attic"))
        assertEquals(listOf(listOf(Area("kitchen", "Kitchen"), Area("attic", "Attic"))), source.areaWrites)
    }

    @Test
    fun `a stale card is dimmed and neither a tap nor a sheet command sends`() = runTest(dispatcher) {
        source.stateMap.value = mapOf(
            "lamp" to EntityState("lamp", StateValue.Light(true, 255), stale = true),
            "shutter" to EntityState("shutter", StateValue.Unknown, stale = true),
        )
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher, preferences = testPreferences())
        backgroundScope.launch { vm.state.collect {} }
        val events = mutableListOf<HomeEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }

        val lamp = vm.state.value.cards.first { it.id == "lamp" }
        assertTrue(lamp.stale)
        vm.tap(lamp)
        vm.send("shutter", Command.Open)
        assertEquals(emptyList(), source.sent)
        assertEquals(listOf<HomeEvent>(HomeEvent.WaitingForConnection, HomeEvent.WaitingForConnection), events)
    }

    @Test
    fun `a live state is not stale and sends`() = runTest(dispatcher) {
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher, preferences = testPreferences())
        backgroundScope.launch { vm.state.collect {} }
        val lamp = vm.state.value.cards.first { it.id == "lamp" }
        assertFalse(lamp.stale)
        vm.tap(lamp)
        assertEquals(listOf<Pair<String, Command>>("lamp" to Command.TurnOff), source.sent)
    }

    @Test
    fun `search query filters and survives recreation`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        HomeViewModel(source, handle, dispatcher, preferences = testPreferences()).search("shut")
        val recreated = HomeViewModel(source, handle, dispatcher, preferences = testPreferences())
        backgroundScope.launch { recreated.state.collect {} }
        assertEquals("shut", recreated.state.value.query)
        assertEquals(listOf("shutter"), recreated.state.value.cards.map { it.id })
    }

    private fun groupSource() = FakeEntitySource().apply {
        entities.value = mapOf(
            "a" to testEntity("a", Kind.COVER, "Bay", area = "living", capabilities = Capabilities(position = true)),
            "b" to testEntity("b", Kind.COVER, "Side", area = "living", capabilities = Capabilities(position = true)),
            "c" to testEntity("c", Kind.COVER, "Patio", area = "living"),
        )
        areas.value = listOf(Area("living", "Living room"))
    }

    @Test
    fun `a group send reaches every available member`() = runTest(dispatcher) {
        val src = groupSource()
        val vm = HomeViewModel(src, SavedStateHandle(), dispatcher, preferences = testPreferences())
        backgroundScope.launch { vm.state.collect {} }
        vm.sendGroup("group:living:cover", Command.Close)
        assertEquals(setOf("a" to Command.Close, "b" to Command.Close, "c" to Command.Close), src.sent.toSet())
    }

    @Test
    fun `unavailable members are skipped`() = runTest(dispatcher) {
        val src = groupSource().apply { stateMap.value = mapOf("c" to EntityState("c", StateValue.Cover(CoverState.OPEN, null), available = false)) }
        val vm = HomeViewModel(src, SavedStateHandle(), dispatcher, preferences = testPreferences())
        backgroundScope.launch { vm.state.collect {} }
        vm.sendGroup("group:living:cover", Command.Open)
        assertEquals(setOf("a", "b"), src.sent.map { it.first }.toSet())
    }

    @Test
    fun `a stale member blocks the whole group send`() = runTest(dispatcher) {
        val src = groupSource().apply { stateMap.value = mapOf("a" to EntityState("a", StateValue.Cover(CoverState.OPEN, 100), stale = true)) }
        val vm = HomeViewModel(src, SavedStateHandle(), dispatcher, preferences = testPreferences())
        backgroundScope.launch { vm.state.collect {} }
        val event = async { vm.events.first() }
        vm.sendGroup("group:living:cover", Command.Open)
        assertEquals(HomeEvent.WaitingForConnection, event.await())
        assertTrue(src.sent.isEmpty())
    }

    @Test
    fun `only resends to the listed members`() = runTest(dispatcher) {
        val src = groupSource()
        val vm = HomeViewModel(src, SavedStateHandle(), dispatcher, preferences = testPreferences())
        backgroundScope.launch { vm.state.collect {} }
        vm.sendGroup("group:living:cover", Command.Open, only = listOf("b"))
        assertEquals(listOf<Pair<String, Command>>("b" to Command.Open), src.sent)
    }

    @Test
    fun `failures of one group send merge into one event`() = runTest(dispatcher) {
        val src = groupSource()
        val vm = HomeViewModel(src, SavedStateHandle(), dispatcher, preferences = testPreferences())
        backgroundScope.launch { vm.state.collect {} }
        val events = mutableListOf<HomeEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        vm.sendGroup("group:living:cover", Command.Close)
        src.commandFailures.emit(CommandFailure("a", Command.Close, "timeout"))
        src.commandFailures.emit(CommandFailure("c", Command.Close, "timeout"))
        advanceTimeBy(1_100)
        assertEquals(
            listOf<HomeEvent>(HomeEvent.GroupNotConfirmed("group:living:cover", Kind.COVER, listOf("a", "c"), listOf("Bay", "Patio"), Command.Close)),
            events,
        )
    }

    @Test
    fun `a failure for a member sent on its own stays a single NotConfirmed`() = runTest(dispatcher) {
        val src = groupSource()
        val vm = HomeViewModel(src, SavedStateHandle(), dispatcher, preferences = testPreferences())
        backgroundScope.launch { vm.state.collect {} }
        val events = mutableListOf<HomeEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        vm.sendGroup("group:living:cover", Command.Close)
        vm.send("a", Command.Open)
        src.commandFailures.emit(CommandFailure("a", Command.Open, "timeout"))
        advanceTimeBy(1_100)
        assertEquals(listOf<HomeEvent>(HomeEvent.NotConfirmed("a", Command.Open)), events)
    }

    @Test
    fun `tapping a light group toggles every light`() = runTest(dispatcher) {
        val src = FakeEntitySource().apply {
            entities.value = mapOf(
                "x" to testEntity("x", Kind.LIGHT, area = "living"),
                "y" to testEntity("y", Kind.LIGHT, area = "living"),
            )
            areas.value = listOf(Area("living", "Living room"))
            stateMap.value = mapOf("x" to EntityState("x", StateValue.Light(true, 255)))
        }
        val vm = HomeViewModel(src, SavedStateHandle(), dispatcher, preferences = testPreferences())
        backgroundScope.launch { vm.state.collect {} }
        vm.tap(vm.state.value.cards.single())
        assertEquals(setOf("x" to Command.TurnOff, "y" to Command.TurnOff), src.sent.toSet())
    }

    @Test
    fun `a rejected group send reports once`() = runTest(dispatcher) {
        val src = groupSource().apply { result = CommandResult.Rejected("read-only") }
        val vm = HomeViewModel(src, SavedStateHandle(), dispatcher, preferences = testPreferences())
        backgroundScope.launch { vm.state.collect {} }
        val events = mutableListOf<HomeEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        vm.sendGroup("group:living:cover", Command.Open)
        assertEquals(listOf<HomeEvent>(HomeEvent.Rejected("group:living:cover", "read-only")), events)
    }

    @Test
    fun `customizing a group renames it and ungroups the unticked members`() = runTest(dispatcher) {
        val src = groupSource()
        val vm = HomeViewModel(src, SavedStateHandle(), dispatcher, preferences = testPreferences())
        backgroundScope.launch { vm.state.collect {} }
        vm.customizeGroup("group:living:cover", "Baie", favorite = false, removed = listOf("c"))
        assertEquals(mapOf("cover" to "Baie"), src.areas.value.single().groupNames)
        assertTrue(src.overrides.getValue("c").ungrouped)
        assertEquals(listOf("a", "b"), vm.state.value.cards.single { it.isGroup }.members.map { it.id })
    }

    @Test
    fun `failures of two different group sends never merge, so Retry repeats the right command`() = runTest(dispatcher) {
        val src = groupSource()
        val vm = HomeViewModel(src, SavedStateHandle(), dispatcher, preferences = testPreferences())
        backgroundScope.launch { vm.state.collect {} }
        val events = mutableListOf<HomeEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        vm.sendGroup("group:living:cover", Command.Open)
        vm.sendGroup("group:living:cover", Command.SetPosition(30))
        src.commandFailures.emit(CommandFailure("c", Command.Open, "timeout"))
        advanceTimeBy(300)
        src.commandFailures.emit(CommandFailure("a", Command.SetPosition(30), "timeout"))
        src.commandFailures.emit(CommandFailure("b", Command.SetPosition(30), "timeout"))
        advanceTimeBy(1_100)
        assertEquals(
            setOf<HomeEvent>(
                HomeEvent.GroupNotConfirmed("group:living:cover", Kind.COVER, listOf("c"), listOf("Patio"), Command.Open),
                HomeEvent.GroupNotConfirmed("group:living:cover", Kind.COVER, listOf("a", "b"), listOf("Bay", "Side"), Command.SetPosition(30)),
            ),
            events.toSet(),
        )
    }
}

private fun testPreferences(): AppPreferences =
    AppPreferences.create(File.createTempFile("prefs", ".json").also { it.delete() }, CoroutineScope(Dispatchers.Unconfined))
