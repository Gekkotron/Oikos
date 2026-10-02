package com.gekkotron.oikos.feature.settings.rooms

import com.gekkotron.oikos.feature.settings.FakeEntitySource
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.UNASSIGNED_AREA
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RoomOrderViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val source = FakeEntitySource().apply {
        areas.value = listOf(Area("bath", "Bath"), Area("living", "Living"), Area("office", "Office"), Area(UNASSIGNED_AREA, UNASSIGNED_AREA))
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun ids(vm: RoomOrderViewModel) = vm.state.value.rooms.map { it.id }

    @Test
    fun `unassigned is split off the reorderable list`() = runTest(dispatcher) {
        val vm = RoomOrderViewModel(source)
        backgroundScope.launch { vm.state.collect {} }
        assertEquals(listOf("bath", "living", "office"), ids(vm))
        assertTrue(vm.state.value.hasUnassigned)
    }

    @Test
    fun `a drop publishes once, an unchanged drop not at all`() = runTest(dispatcher) {
        val vm = RoomOrderViewModel(source)
        backgroundScope.launch { vm.state.collect {} }
        vm.commit(listOf("bath", "living", "office"))
        assertEquals(0, source.published.size)
        vm.commit(listOf("office", "bath", "living"))
        assertEquals(1, source.published.size)
        assertEquals(listOf("office", "bath", "living"), ids(vm))
    }

    @Test
    fun `move up and move down publish at once, and stop at the ends`() = runTest(dispatcher) {
        val vm = RoomOrderViewModel(source)
        backgroundScope.launch { vm.state.collect {} }
        vm.move("office", -1)
        assertEquals(listOf("bath", "office", "living"), ids(vm))
        vm.move("bath", -1)
        assertEquals(1, source.published.size)
    }

    @Test
    fun `a failed publish emits SaveFailed and keeps the old order`() = runTest(dispatcher) {
        val vm = RoomOrderViewModel(source)
        backgroundScope.launch { vm.state.collect {} }
        val events = mutableListOf<RoomOrderEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        source.failWrites = true
        vm.commit(listOf("living", "bath", "office"))
        assertEquals(listOf<RoomOrderEvent>(RoomOrderEvent.SaveFailed), events)
        assertEquals(listOf("bath", "living", "office"), ids(vm))
    }
}
