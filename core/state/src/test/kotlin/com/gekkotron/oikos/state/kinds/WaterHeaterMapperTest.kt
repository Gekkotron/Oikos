package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue.Climate
import com.gekkotron.oikos.model.StateValue.Unknown
import com.gekkotron.oikos.state.bindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WaterHeaterMapperTest {
    // What jMQTT publishes to spec Appendix B.1 for the force-heat heater.
    private val jeedom = bindings(
        "water_heater",
        """{"current_temperature_topic":"water-heater/data",
           "current_temperature_template":"{{ value_json.temp_0 }}",
           "mode_state_topic":"jeedom/water_heater/state",
           "mode_state_template":"{{ value_json.mode }}",
           "temperature_state_topic":"jeedom/water_heater/state",
           "temperature_state_template":"{{ value_json.target }}",
           "mode_command_topic":"jeedom/water_heater/mode/set",
           "temperature_command_topic":"jeedom/water_heater/temperature/set",
           "modes":["off","eco","performance"],
           "min_temp":40,"max_temp":65,"precision":1.0}""",
    )

    private fun publishes(topic: String, payload: String) = Encoded.Publish(listOf(Outgoing(topic, payload, false, 0)))

    @Test
    fun `current temperature from its own topic`() {
        assertEquals(
            Decoded.State(Climate(null, null, 52.3)),
            WaterHeaterMapper.decode(jeedom, "water-heater/data", """{"temp_0":52.3,"triac_0_power":30}""", Unknown),
        )
    }

    @Test
    fun `mode and target on one JSON topic`() {
        assertEquals(
            Decoded.State(Climate("performance", 60.0, null)),
            WaterHeaterMapper.decode(jeedom, "jeedom/water_heater/state", """{"mode":"performance","target":60}""", Unknown),
        )
    }

    @Test
    fun `mode must be one of the declared modes`() {
        assertEquals(
            Decoded.State(Climate("eco", 55.0, null)),
            WaterHeaterMapper.decode(jeedom, "jeedom/water_heater/state", """{"mode":"eco","target":55}""", Unknown),
        )
        // An undeclared mode is dropped (mode stays unset); the valid target on the same payload still applies.
        assertEquals(
            Decoded.State(Climate(null, 55.0, null)),
            WaterHeaterMapper.decode(jeedom, "jeedom/water_heater/state", """{"mode":"turbo","target":55}""", Unknown),
        )
    }

    @Test
    fun `SetTemperature is range checked and formatted`() {
        assertEquals(
            publishes("jeedom/water_heater/temperature/set", "58"),
            WaterHeaterMapper.encode(jeedom, Command.SetTemperature(58.0), Unknown),
        )
        assertEquals(
            publishes("jeedom/water_heater/temperature/set", "58.5"),
            WaterHeaterMapper.encode(jeedom, Command.SetTemperature(58.5), Unknown),
        )
        assertIs<Encoded.NotSupported>(WaterHeaterMapper.encode(jeedom, Command.SetTemperature(10.0), Unknown))
        assertIs<Encoded.NotSupported>(WaterHeaterMapper.encode(jeedom, Command.SetTemperature(80.0), Unknown))
    }

    @Test
    fun `SetHvacMode publishes known modes only`() {
        assertEquals(
            publishes("jeedom/water_heater/mode/set", "performance"),
            WaterHeaterMapper.encode(jeedom, Command.SetHvacMode("performance"), Unknown),
        )
        assertEquals(
            publishes("jeedom/water_heater/mode/set", "eco"),
            WaterHeaterMapper.encode(jeedom, Command.SetHvacMode("eco"), Unknown),
        )
        assertIs<Encoded.NotSupported>(WaterHeaterMapper.encode(jeedom, Command.SetHvacMode("turbo"), Unknown))
    }

    @Test
    fun `TurnOff is accepted only when off is in the modes`() {
        assertEquals(
            publishes("jeedom/water_heater/mode/set", "off"),
            WaterHeaterMapper.encode(jeedom, Command.TurnOff, Unknown),
        )
        val noOff = bindings(
            "water_heater",
            """{"mode_command_topic":"m/set","modes":["eco","performance"]}""",
        )
        assertIs<Encoded.NotSupported>(WaterHeaterMapper.encode(noOff, Command.TurnOff, Unknown))
    }

    @Test
    fun `defaults when the config is sparse`() {
        val sparse = bindings("water_heater", "{}")
        assertEquals(WaterHeaterMapper.DEFAULT_MODES, WaterHeaterMapper.modes(sparse))
        assertEquals(emptySet(), WaterHeaterMapper.stateTopics(sparse))
    }

    @Test
    fun `prediction and feedback topics`() {
        assertEquals(
            Climate("performance", 60.0, 52.0),
            WaterHeaterMapper.predict(Command.SetHvacMode("performance"), Climate("eco", 60.0, 52.0)),
        )
        assertEquals(
            Climate(null, 58.0, null),
            WaterHeaterMapper.predict(Command.SetTemperature(58.0), Unknown),
        )
        assertEquals(
            setOf("jeedom/water_heater/state"),
            WaterHeaterMapper.feedbackTopics(jeedom, Command.SetHvacMode("eco")),
        )
        assertEquals(
            setOf("jeedom/water_heater/state"),
            WaterHeaterMapper.feedbackTopics(jeedom, Command.SetTemperature(58.0)),
        )
    }
}
