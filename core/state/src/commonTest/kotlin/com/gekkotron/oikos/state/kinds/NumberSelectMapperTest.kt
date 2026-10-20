package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.StateValue.Unknown
import com.gekkotron.oikos.state.bindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NumberSelectMapperTest {
    private val number = bindings("number", """{"state_topic":"n","command_topic":"n/set","min":10,"max":60}""")
    private val select = bindings("select", """{"state_topic":"s","command_topic":"s/set","options":["eco","comfort"]}""")

    @Test
    fun `number decodes and range-checks`() {
        assertEquals(Decoded.State(StateValue.Numeric(42.0)), NumberMapper.decode(number, "n", "42", Unknown))
        assertEquals(Decoded.Ignored, NumberMapper.decode(number, "n", "abc", Unknown))
        assertEquals(Encoded.Publish(listOf(Outgoing("n/set", "45", false, 0))), NumberMapper.encode(number, Command.SetNumber(45.0), Unknown))
        assertIs<Encoded.NotSupported>(NumberMapper.encode(number, Command.SetNumber(5.0), Unknown))
        assertEquals(StateValue.Numeric(45.0), NumberMapper.predict(Command.SetNumber(45.0), Unknown))
    }

    @Test
    fun `number defaults are 1 to 100`() {
        val b = bindings("number", """{"command_topic":"n/set"}""")
        assertIs<Encoded.NotSupported>(NumberMapper.encode(b, Command.SetNumber(0.0), Unknown))
        assertIs<Encoded.Publish>(NumberMapper.encode(b, Command.SetNumber(100.0), Unknown))
    }

    @Test
    fun `select accepts only listed options`() {
        assertEquals(Decoded.State(StateValue.Text("eco")), SelectMapper.decode(select, "s", "eco", Unknown))
        assertEquals(Decoded.Ignored, SelectMapper.decode(select, "s", "boost", Unknown))
        assertEquals(Encoded.Publish(listOf(Outgoing("s/set", "comfort", false, 0))), SelectMapper.encode(select, Command.SelectOption("comfort"), Unknown))
        assertIs<Encoded.NotSupported>(SelectMapper.encode(select, Command.SelectOption("boost"), Unknown))
        assertEquals(StateValue.Text("eco"), SelectMapper.predict(Command.SelectOption("eco"), Unknown))
    }

    @Test
    fun `command template can do arithmetic on the commanded value`() {
        val tenths = bindings("number", """{"state_topic":"n","command_topic":"n/set","command_template":"{{ value * 10 }}","min":10,"max":60}""")
        assertEquals(Encoded.Publish(listOf(Outgoing("n/set", "215.0", false, 0))), NumberMapper.encode(tenths, Command.SetNumber(21.5), Unknown))
    }
}
