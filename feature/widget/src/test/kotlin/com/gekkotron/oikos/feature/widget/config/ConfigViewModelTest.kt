package com.gekkotron.oikos.feature.widget.config

import com.gekkotron.oikos.feature.widget.CatalogResult
import com.gekkotron.oikos.feature.widget.Failure
import com.gekkotron.oikos.feature.widget.testing.FakeScheduler
import com.gekkotron.oikos.feature.widget.testing.FakeWidgetEntities
import com.gekkotron.oikos.feature.widget.testing.MemConfigs
import com.gekkotron.oikos.feature.widget.model.WidgetConfig
import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Bindings
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.UiOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigViewModelTest {
    private object B : Bindings

    private fun e(id: String, name: String, kind: Kind = Kind.SWITCH, area: String = "living", caps: Capabilities = Capabilities()) =
        Entity(id, null, name, kind, null, null, null, caps, B, area = area)

    private val areas = listOf(Area("living", "Living room"), Area("bed", "Bedroom"))
    private val alarm = e("al", "Alarm", Kind.ALARM_PANEL, caps = Capabilities(alarmModes = setOf(AlarmMode.HOME, AlarmMode.AWAY)))
    private val catalog = CatalogResult.Ok(listOf(e("b", "Bed lamp", area = "bed"), e("a", "Kettle"), alarm), areas)

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    private fun vm(result: CatalogResult = catalog, configs: MemConfigs = MemConfigs(), scheduler: FakeScheduler = FakeScheduler()) =
        ConfigViewModel(1, FakeWidgetEntities(catalog = result), configs, scheduler, fitCount = 4)

    @Test
    fun rows_grouped_by_area_order_then_name_and_searchable() = runTest {
        val vm = vm()
        assertEquals(listOf("Alarm", "Kettle", "Bed lamp"), vm.state.value.rows.map { it.name })
        vm.search("lamp")
        assertEquals(listOf("Bed lamp"), vm.state.value.rows.map { it.name })
        vm.search("living")
        assertEquals(listOf("Alarm", "Kettle"), vm.state.value.rows.map { it.name })
    }

    @Test
    fun hidden_and_folded_entities_are_not_offered() = runTest {
        val hidden = e("h", "Hidden").copy(ui = UiOverride(hidden = true))
        val folded = e("f", "Moving").copy(partOf = "a")
        val vm = vm(CatalogResult.Ok(listOf(e("a", "Kettle"), hidden, folded), areas))
        assertEquals(listOf("Kettle"), vm.state.value.rows.map { it.name })
    }

    @Test
    fun existing_config_is_preselected() = runTest {
        val configs = MemConfigs(mutableMapOf(1 to WidgetConfig(listOf("b", "a"), AlarmMode.HOME)))
        val vm = vm(configs = configs)
        assertEquals(listOf("b", "a"), vm.state.value.selected)
        assertEquals(AlarmMode.HOME, vm.state.value.armMode)
    }

    @Test
    fun selection_caps_at_eight() = runTest {
        val vm = vm(CatalogResult.Ok((1..10).map { e("e$it", "E$it") }, areas))
        (1..10).forEach { vm.toggle("e$it") }
        assertEquals(8, vm.state.value.selected.size)
        vm.toggle("e1")
        assertEquals(7, vm.state.value.selected.size)
    }

    @Test
    fun save_writes_config_schedules_and_refreshes() = runTest {
        val configs = MemConfigs()
        val scheduler = FakeScheduler()
        val vm = vm(configs = configs, scheduler = scheduler)
        vm.toggle("a")
        vm.toggle("al")
        vm.setArmMode(AlarmMode.AWAY)
        vm.move(1, 0)
        assertTrue(vm.save())
        assertEquals(WidgetConfig(listOf("al", "a"), AlarmMode.AWAY), configs.map[1])
        assertEquals(1, scheduler.periodic)
        assertEquals(1, scheduler.now)
    }

    @Test
    fun alarm_row_appears_only_when_an_alarm_is_selected() = runTest {
        val vm = vm()
        assertEquals(null, vm.state.value.alarmRow)
        vm.toggle("al")
        assertEquals("al", vm.state.value.alarmRow?.id)
    }

    @Test
    fun move_out_of_range_is_ignored() = runTest {
        val vm = vm()
        vm.toggle("a")
        vm.move(0, 5)
        assertEquals(listOf("a"), vm.state.value.selected)
    }

    @Test
    fun save_with_nothing_selected_is_refused() = runTest {
        assertFalse(vm().save())
    }

    @Test
    fun not_configured_and_offline_flags() = runTest {
        assertTrue(vm(CatalogResult.Failed(Failure.NOT_CONFIGURED)).state.value.notConfigured)
        assertTrue(vm(CatalogResult.Failed(Failure.OFFLINE)).state.value.offline)
        assertFalse(vm().state.value.loading)
    }
}
