package com.gekkotron.oikos.feature.history.presentation

import com.gekkotron.oikos.feature.history.FakeEnergyHistory
import com.gekkotron.oikos.feature.history.FakeEntitySource
import com.gekkotron.oikos.feature.history.FakeHistoryClient
import com.gekkotron.oikos.feature.history.HistoryTarget
import com.gekkotron.oikos.history.HistoryRange
import com.gekkotron.oikos.history.HistoryResult
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val history = FakeHistoryClient()
    private val energy = FakeEnergyHistory()
    private val source = FakeEntitySource().apply {
        entities.value = listOf(
            sensor("thermo", "°C", "temperature"),
            sensor("plug_power", "W", "power"),
            sensor("plug_energy", "kWh", "energy"),
        ).associateBy { it.uniqueId }
        stateMap.value = mapOf("thermo" to EntityState("thermo", StateValue.Numeric(21.5)))
    }

    private fun sensor(id: String, unit: String, key: String) = Entity(
        uniqueId = id, deviceId = null, name = id, kind = Kind.SENSOR, deviceClass = null, unit = unit, icon = null,
        capabilities = Capabilities(),
        bindings = MqttBindings(
            "sensor",
            JsonObject(mapOf("state_topic" to JsonPrimitive("z/$id"), "value_template" to JsonPrimitive("{{ value_json.$key }}"))),
        ),
    )

    private fun vm() = HistoryViewModel(source, history, energy)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a sensor shows its 24 h line with stats`() = runTest(dispatcher) {
        val vm = vm()
        vm.bind(HistoryTarget.Entity("thermo"))
        backgroundScope.launch { vm.state.collect {} }
        val line = vm.state.first { it.content is ChartContent.Line }.content as ChartContent.Line
        assertEquals(Triple(19.0, 21.0, 23.0), Triple(line.min, line.avg, line.max))
        assertEquals(listOf("series temperature 24h"), history.calls)
        assertFalse(vm.state.value.energy)
        assertEquals(21.5, vm.state.value.live)
    }

    @Test
    fun `empty series is NoData, not NotRecorded`() = runTest(dispatcher) {
        history.series = { _, _ -> HistoryResult.Ok(com.gekkotron.oikos.history.Series(600, emptyList())) }
        val vm = vm()
        vm.bind(HistoryTarget.Entity("thermo"))
        backgroundScope.launch { vm.state.collect {} }
        vm.state.first { it.content == ChartContent.NoData }
    }

    @Test
    fun `results map to states`() = runTest(dispatcher) {
        listOf(
            HistoryResult.NotRecorded to ChartContent.NotRecorded,
            HistoryResult.NotAllowed to ChartContent.NotAllowed,
            HistoryResult.Unavailable("x") to ChartContent.Unavailable,
        ).forEach { (r, c) ->
            history.series = { _, _ -> r }
            val vm = vm()
            vm.bind(HistoryTarget.Entity("thermo"))
            backgroundScope.launch { vm.state.collect {} }
            vm.state.first { it.content == c }
        }
    }

    @Test
    fun `a power sensor's daily bars use its paired meter`() = runTest(dispatcher) {
        energy.meters["plug_power"] = "plug_energy"
        val vm = vm()
        vm.bind(HistoryTarget.Entity("plug_power"))
        backgroundScope.launch { vm.state.collect {} }
        vm.selectRange(HistoryRange.D7)
        vm.selectMode(ChartMode.DAILY)
        val bars = vm.state.first { it.content is ChartContent.Bars }.content as ChartContent.Bars
        assertEquals(7.5, bars.total)
        assertEquals("daily energy 7 COUNTER", history.calls.last())
    }

    @Test
    fun `without a meter the daily bars integrate power`() = runTest(dispatcher) {
        val vm = vm()
        vm.bind(HistoryTarget.Entity("plug_power"))
        backgroundScope.launch { vm.state.collect {} }
        vm.selectRange(HistoryRange.D30)
        vm.selectMode(ChartMode.DAILY)
        vm.state.first { it.content is ChartContent.Bars }
        assertEquals("daily power 30 POWER", history.calls.last())
    }

    @Test
    fun `a kWh meter goes straight to daily bars on 7 d and has no toggle`() = runTest(dispatcher) {
        val vm = vm()
        vm.bind(HistoryTarget.Entity("plug_energy"))
        backgroundScope.launch { vm.state.collect {} }
        vm.selectRange(HistoryRange.D7)
        val ui = vm.state.first { it.content is ChartContent.Bars }
        assertEquals(ChartMode.DAILY, ui.mode)
        assertFalse(ui.toggle)
        assertEquals("daily energy 7 COUNTER", history.calls.last())
    }

    @Test
    fun `a kWh meter shows its index line on 24 h`() = runTest(dispatcher) {
        val vm = vm()
        vm.bind(HistoryTarget.Entity("plug_energy"))
        backgroundScope.launch { vm.state.collect {} }
        val ui = vm.state.first { it.content is ChartContent.Line }
        assertEquals(ChartMode.POWER, ui.mode)
        assertFalse(ui.toggle)
    }

    @Test
    fun `power sensors and the house get the toggle`() = runTest(dispatcher) {
        val vm = vm()
        backgroundScope.launch { vm.state.collect {} }
        vm.bind(HistoryTarget.Entity("plug_power"))
        assertTrue(vm.state.first { it.energy }.toggle)
        vm.bind(HistoryTarget.House)
        assertTrue(vm.state.first { it.title == null && it.energy }.toggle)
    }

    @Test
    fun `24 h always shows power`() = runTest(dispatcher) {
        val vm = vm()
        vm.bind(HistoryTarget.Entity("plug_power"))
        backgroundScope.launch { vm.state.collect {} }
        vm.selectRange(HistoryRange.D7)
        vm.selectMode(ChartMode.DAILY)
        vm.selectRange(HistoryRange.H24)
        assertEquals(ChartMode.POWER, vm.state.first { it.range == HistoryRange.H24 }.mode)
    }

    @Test
    fun `a late answer for an older range is dropped`() = runTest(dispatcher) {
        history.gate = CompletableDeferred()
        val vm = vm()
        vm.bind(HistoryTarget.Entity("thermo"))
        backgroundScope.launch { vm.state.collect {} }
        vm.selectRange(HistoryRange.D7)
        vm.state.first { it.range == HistoryRange.D7 && it.content is ChartContent.Line }
        history.gate!!.complete(Unit)
        assertEquals(HistoryRange.D7, vm.state.value.range)
        assertTrue(vm.state.value.content is ChartContent.Line)
    }

    @Test
    fun `the house uses the energy history`() = runTest(dispatcher) {
        val vm = vm()
        vm.bind(HistoryTarget.House)
        backgroundScope.launch { vm.state.collect {} }
        vm.state.first { it.content is ChartContent.Line && it.energy && it.title == null }
    }

    @Test
    fun `a gone entity or one without a ref is missing`() = runTest(dispatcher) {
        val vm = vm()
        vm.bind(HistoryTarget.Entity("nope"))
        backgroundScope.launch { vm.state.collect {} }
        vm.state.first { it.missing }
    }

    @Test
    fun `target encoding round-trips`() {
        listOf(HistoryTarget.House, HistoryTarget.Entity("a:b")).forEach { assertEquals(it, HistoryTarget.decode(it.encode())) }
        assertNull(HistoryTarget.decode("x"))
    }
}
