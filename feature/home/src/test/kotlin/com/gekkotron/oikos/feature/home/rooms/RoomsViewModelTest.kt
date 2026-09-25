package com.gekkotron.oikos.feature.home.rooms

import androidx.lifecycle.SavedStateHandle
import com.gekkotron.oikos.feature.home.FakeEntitySource
import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Device
import com.gekkotron.oikos.model.Kind
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RoomsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val source = FakeEntitySource().apply {
        entities.value = listOf(
            testEntity("k1", Kind.SWITCH, deviceId = "kettle"),
            testEntity("k2", Kind.SENSOR, deviceId = "kettle", ui = UiOverride(favorite = true)),
        ).associateBy { it.uniqueId }
        devices.value = mapOf("kettle" to Device("kettle", "Kettle", null, null, null))
        areas.value = listOf(Area("kitchen", "Kitchen"))
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `assigning a row writes every entity of the device and marks the screen changed`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val vm = RoomsViewModel(source, handle, dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        assertFalse(vm.state.value.changed)
        vm.assign("kettle", "kitchen")
        assertEquals(mapOf("k1" to UiOverride(area = "kitchen"), "k2" to UiOverride(favorite = true, area = "kitchen")), source.overrides)
        assertTrue(vm.state.value.changed)
        val recreated = RoomsViewModel(source, handle, dispatcher)
        backgroundScope.launch { recreated.state.collect {} }
        assertTrue(recreated.state.value.changed)
    }

    @Test
    fun `a failed assignment emits SaveFailed and does not mark the screen changed`() = runTest(dispatcher) {
        source.failWrites = true
        val vm = RoomsViewModel(source, SavedStateHandle(), dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        vm.assign("kettle", "kitchen")
        assertEquals(RoomsEvent.SaveFailed, vm.events.first())
        assertFalse(vm.state.value.changed)
    }

    @Test
    fun `createRoom reuses or writes like Home`() = runTest(dispatcher) {
        val vm = RoomsViewModel(source, SavedStateHandle(), dispatcher)
        assertEquals("kitchen", vm.createRoom("Kitchen"))
        assertEquals("garage", vm.createRoom("Garage"))
        assertEquals(listOf(listOf(Area("kitchen", "Kitchen"), Area("garage", "Garage"))), source.areaWrites)
    }
}
