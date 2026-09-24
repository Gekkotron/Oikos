package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.StateValue.Unknown
import com.gekkotron.oikos.state.bindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SensorMapperTest {
    private val temp = bindings("sensor", """{"state_topic":"z","value_template":"{{ value_json.temperature }}"}""")
    private val raw = bindings("sensor", """{"state_topic":"s"}""")

    @Test
    fun `numeric values`() {
        assertEquals(Decoded.State(StateValue.Numeric(21.5)), SensorMapper.decode(temp, "z", """{"temperature":21.5}""", Unknown))
        assertEquals(Decoded.State(StateValue.Numeric(1234.0)), SensorMapper.decode(raw, "s", "1234", Unknown))
    }

    @Test
    fun `text values`() {
        assertEquals(Decoded.State(StateValue.Text("idle")), SensorMapper.decode(raw, "s", "idle", Unknown))
    }

    @Test
    fun `missing, blank and unknown become Unknown`() {
        assertEquals(Decoded.State(Unknown), SensorMapper.decode(temp, "z", """{"humidity":40}""", Unknown))
        assertEquals(Decoded.State(Unknown), SensorMapper.decode(raw, "s", "", Unknown))
        assertEquals(Decoded.State(Unknown), SensorMapper.decode(raw, "s", "unavailable", Unknown))
    }

    @Test
    fun `errors and unsupported templates are surfaced`() {
        assertIs<Decoded.Error>(SensorMapper.decode(temp, "z", "online", Unknown))
        val weird = bindings("sensor", """{"state_topic":"s","value_template":"{{ value | tojson }}"}""")
        assertIs<Decoded.Unsupported>(SensorMapper.decode(weird, "s", "1", Unknown))
    }
}
