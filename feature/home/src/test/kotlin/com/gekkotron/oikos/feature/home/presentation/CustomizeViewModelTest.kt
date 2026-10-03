package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.feature.home.FakeEntitySource
import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.UiOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class CustomizeViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val source = FakeEntitySource().apply {
        entities.value = mapOf("door" to testEntity("door", Kind.BINARY_SENSOR, "Front door", area = "kitchen", deviceClass = "door"))
        areas.value = listOf(Area("kitchen", "Kitchen"))
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a device Home does not show is ready to customise, an unknown one is gone`() = runTest(dispatcher) {
        val vm = CustomizeViewModel(source, LabelText { null })
        assertEquals("Front door", assertIs<CustomizeLoad.Ready>(vm.load("door").first()).card.name)
        assertEquals(listOf(Area("kitchen", "Kitchen")), assertIs<CustomizeLoad.Ready>(vm.load("door").first()).rooms)
        assertEquals(CustomizeLoad.Gone, vm.load("nope").first())
    }

    @Test
    fun `nothing is decided before the entities arrive`() = runTest(dispatcher) {
        source.entities.value = emptyMap()
        val vm = CustomizeViewModel(source, LabelText { null })
        assertEquals(CustomizeLoad.Loading, vm.load("door").first())
    }

    @Test
    fun `save writes the override, and a failure is reported`() = runTest(dispatcher) {
        val vm = CustomizeViewModel(source, LabelText { null })
        var failures = 0
        vm.save("door", emptyList(), UiOverride(name = "Entrance")) { failures++ }
        assertEquals(UiOverride(name = "Entrance"), source.overrides["door"])
        source.failWrites = true
        vm.save("door", emptyList(), UiOverride(name = "Hall")) { failures++ }
        assertEquals(1, failures)
    }
}
