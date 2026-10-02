package com.gekkotron.oikos.demo

import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandFailure
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Diagnostic
import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.source.MqttDiscoverySource
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.PublishFailed
import com.gekkotron.oikos.transport.TransportMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DemoBrokerTest {
    private val allIds = setOf(
        "demo_living_lamp", "demo_kitchen_spots", "demo_coffee", "demo_garage_plug", "demo_living_shutter",
        "demo_living_ac", "demo_bedroom_temperature", "demo_bedroom_humidity", "demo_outdoor_temperature",
        "demo_solar_power", "demo_grid_power", "demo_house_power", "demo_water_heater_power", "demo_ac_power", "demo_front_door", "demo_hallway_motion",
        "demo_alarm", "demo_water_heater_target", "demo_vacuum_mode", "demo_vacuum_start",
        "demo_bedroom_strip", "demo_bedroom_strip_effect_speed", "demo_bedroom_strip_effect_intensity",
    )

    private class Harness(val broker: DemoBroker, val source: MqttDiscoverySource, val diagnostics: List<Diagnostic>)

    private fun TestScope.start(): Harness {
        val broker = DemoBroker(backgroundScope)
        val diagnostics = mutableListOf<Diagnostic>()
        val source = MqttDiscoverySource(broker, backgroundScope, diagnostics = { diagnostics += it })
        runCurrent()
        settle()
        return Harness(broker, source, diagnostics)
    }

    /** Past the subscription debounce and the echo delay. */
    private fun TestScope.settle() {
        advanceTimeBy(MqttDiscoverySource.SUBSCRIPTION_DEBOUNCE.inWholeMilliseconds + 1)
        runCurrent()
        advanceTimeBy(301)
        runCurrent()
    }

    private suspend fun Harness.value(id: String) = source.states().first().getValue(id).value

    @Test
    fun `every demo config becomes an entity without diagnostics`() = runTest {
        val h = start()
        assertEquals(allIds, h.source.entities.value.keys)
        assertEquals(emptyList(), h.diagnostics)
        assertEquals("Floor lamp", h.source.entities.value.getValue("demo_living_lamp").name)
    }

    @Test
    fun `declared areas and overrides are applied`() = runTest {
        val h = start()
        val e = h.source.entities.value
        assertEquals("living_room", e.getValue("demo_living_lamp").area)
        assertEquals(listOf("Living room", "Kitchen", "Bedroom", "Hallway", "Garage"), h.source.areas.value.take(5).map { it.name })
        assertEquals("hero_weather", e.getValue("demo_outdoor_temperature").ui.cardStyle)
        assertTrue(e.getValue("demo_living_lamp").ui.favorite)
        assertEquals(EnergyRole.SOLAR, e.getValue("demo_solar_power").ui.energyRole)
        assertEquals(EnergyRole.CONSUMER, e.getValue("demo_water_heater_power").ui.energyRole)
        assertEquals(EnergyRole.CONSUMER, e.getValue("demo_ac_power").ui.energyRole)
    }

    @Test
    fun `every stateful entity has a known state after settling`() = runTest {
        val h = start()
        val states = h.source.states().first()
        for (id in allIds - "demo_vacuum_start") {
            val v = states.getValue(id).value
            assertTrue(v !is StateValue.Unknown && v !is StateValue.Unsupported, "$id is $v")
        }
        assertEquals(StateValue.Light(true, 180), h.value("demo_living_lamp"))
        assertEquals(StateValue.OnOff(false), h.value("demo_front_door")) // contact=true means closed
    }

    @Test
    fun `toggling the lamp is confirmed and never reverted`() = runTest {
        val h = start()
        val failures = mutableListOf<CommandFailure>()
        backgroundScope.launch { h.source.commandFailures.collect { failures += it } }
        assertEquals(CommandResult.Accepted, h.source.send("demo_living_lamp", Command.Toggle))
        runCurrent()
        assertTrue(h.source.states().first().getValue("demo_living_lamp").pending)
        settle()
        val state = h.source.states().first().getValue("demo_living_lamp")
        assertEquals(StateValue.Light(false, 180), state.value)
        assertFalse(state.pending)
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(emptyList(), failures)
        assertEquals(StateValue.Light(false, 180), h.value("demo_living_lamp"))
    }

    @Test
    fun `cover, climate, number, select and alarm commands are echoed`() = runTest {
        val h = start()
        h.source.send("demo_living_shutter", Command.SetPosition(25))
        h.source.send("demo_living_ac", Command.SetTemperature(21.5))
        h.source.send("demo_living_ac", Command.SetHvacMode("cool"))
        h.source.send("demo_water_heater_target", Command.SetNumber(58.0))
        h.source.send("demo_vacuum_mode", Command.SelectOption("turbo"))
        h.source.send("demo_alarm", Command.Arm(AlarmMode.AWAY))
        settle()
        assertEquals(StateValue.Cover(CoverState.OPEN, 25), h.value("demo_living_shutter"))
        val ac = assertIs<StateValue.Climate>(h.value("demo_living_ac"))
        assertEquals(21.5, ac.target)
        assertEquals("cool", ac.mode)
        assertEquals(StateValue.Numeric(58.0), h.value("demo_water_heater_target"))
        assertEquals(StateValue.Text("turbo"), h.value("demo_vacuum_mode"))
        assertEquals(StateValue.Alarm(AlarmState.ARMED_AWAY), h.value("demo_alarm"))
    }

    @Test
    fun `closing the shutter ends closed at zero`() = runTest {
        val h = start()
        h.source.send("demo_living_shutter", Command.Close)
        settle()
        assertEquals(StateValue.Cover(CoverState.CLOSED, 0), h.value("demo_living_shutter"))
    }

    @Test
    fun `garage plug is unavailable and rejects commands`() = runTest {
        val h = start()
        assertFalse(h.source.states().first().getValue("demo_garage_plug").available)
        assertEquals(CommandResult.Rejected("unavailable"), h.source.send("demo_garage_plug", Command.Toggle))
    }

    @Test
    fun `ticking moves energy values and keeps grid equal to house minus solar`() = runTest {
        val h = start()
        val before = h.value("demo_solar_power")
        h.broker.startTicking(3.seconds)
        repeat(5) {
            advanceTimeBy(3_001)
            runCurrent()
        }
        val solar = assertIs<StateValue.Numeric>(h.value("demo_solar_power")).value
        val grid = assertIs<StateValue.Numeric>(h.value("demo_grid_power")).value
        val house = assertIs<StateValue.Numeric>(h.value("demo_house_power")).value
        assertNotEquals(before, StateValue.Numeric(solar))
        assertEquals(house - solar, grid)
        assertTrue(solar in 0.0..4200.0)
    }

    @Test
    fun `ticking keeps the consumers known`() = runTest {
        val h = start()
        h.broker.startTicking(3.seconds)
        repeat(3) {
            advanceTimeBy(3_001)
            runCurrent()
        }
        assertIs<StateValue.Numeric>(h.value("demo_water_heater_power"))
        assertIs<StateValue.Numeric>(h.value("demo_ac_power"))
    }

    @Test
    fun `link reports demo mode and invalid topics fail`() = runTest {
        val h = start()
        assertEquals(LinkState.Connected(TransportMode.DEMO), h.source.link.value)
        assertFailsWith<PublishFailed> { h.broker.publish("demo/+/set", "x".encodeToByteArray()) }
    }
}
