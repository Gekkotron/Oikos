package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue.Unknown
import com.gekkotron.oikos.state.bindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ButtonMapperTest {
    @Test
    fun `press publishes the press payload`() {
        val b = bindings("button", """{"command_topic":"c"}""")
        assertEquals(Encoded.Publish(listOf(Outgoing("c", "PRESS", false, 0))), ButtonMapper.encode(b, Command.Press, Unknown))
        val custom = bindings("button", """{"command_topic":"c","payload_press":"go"}""")
        assertEquals(Encoded.Publish(listOf(Outgoing("c", "go", false, 0))), ButtonMapper.encode(custom, Command.Press, Unknown))
    }

    @Test
    fun `no state and no prediction`() {
        val b = bindings("button", """{"command_topic":"c"}""")
        assertEquals(emptySet(), ButtonMapper.stateTopics(b))
        assertNull(ButtonMapper.predict(Command.Press, Unknown))
        assertIs<Encoded.NotSupported>(ButtonMapper.encode(b, Command.TurnOn(), Unknown))
    }
}
