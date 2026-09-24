package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue.OnOff
import com.gekkotron.oikos.model.StateValue.Unknown
import com.gekkotron.oikos.state.bindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BinarySensorMapperTest {
    @Test
    fun `default payloads`() {
        val b = bindings("binary_sensor", """{"state_topic":"s"}""")
        assertEquals(Decoded.State(OnOff(true)), BinarySensorMapper.decode(b, "s", "ON", Unknown))
        assertEquals(Decoded.State(OnOff(false)), BinarySensorMapper.decode(b, "s", "OFF", Unknown))
    }

    // Review Focus 1: z2m publishes JSON booleans for payload_on/off and the template renders a boolean.
    @Test
    fun `z2m contact sensor with boolean payloads`() {
        val b = bindings(
            "binary_sensor",
            """{"state_topic":"z","value_template":"{{ value_json.contact }}","payload_on":false,"payload_off":true,"device_class":"door"}""",
        )
        assertEquals(Decoded.State(OnOff(true)), BinarySensorMapper.decode(b, "z", """{"contact":false,"battery":90}""", Unknown))
        assertEquals(Decoded.State(OnOff(false)), BinarySensorMapper.decode(b, "z", """{"contact":true}""", Unknown))
    }

    @Test
    fun `z2m motion sensor`() {
        val b = bindings("binary_sensor", """{"state_topic":"z","value_template":"{{ value_json.occupancy }}","payload_on":true,"payload_off":false}""")
        assertEquals(Decoded.State(OnOff(true)), BinarySensorMapper.decode(b, "z", """{"occupancy":true}""", Unknown))
    }

    @Test
    fun `read-only`() {
        val b = bindings("binary_sensor", """{"state_topic":"s"}""")
        assertIs<Encoded.NotSupported>(BinarySensorMapper.encode(b, Command.TurnOn(), Unknown))
    }
}
