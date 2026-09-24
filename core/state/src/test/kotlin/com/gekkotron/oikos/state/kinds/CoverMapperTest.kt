package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.StateValue.Cover
import com.gekkotron.oikos.model.StateValue.Unknown
import com.gekkotron.oikos.state.bindings
import kotlin.test.Test
import kotlin.test.assertEquals

class CoverMapperTest {
    private val z2m = bindings(
        "cover",
        """{"command_topic":"z/set","state_topic":"z","value_template":"{{ value_json.state }}","state_open":"OPEN","state_closed":"CLOSE",
           "position_topic":"z","position_template":"{{ value_json.position }}",
           "set_position_topic":"z/set","set_position_template":"{ \"position\": {{ position }} }"}""",
    )

    private fun publishes(topic: String, payload: String) = Encoded.Publish(listOf(Outgoing(topic, payload, false, 0)))

    @Test
    fun `state and position on one topic`() {
        assertEquals(Decoded.State(Cover(CoverState.OPEN, 42)), CoverMapper.decode(z2m, "z", """{"position":42,"state":"OPEN"}""", Unknown))
    }

    @Test
    fun `statement template on state still tracks position`() {
        // zigbee2mqtt's real cover value_template uses {% if %}, which is outside the subset.
        val b = bindings(
            "cover",
            """{"state_topic":"z","value_template":"{% if value_json.moving %}{{ value_json.moving }}{% else %}STOP{% endif %}",
               "position_topic":"z","position_template":"{{ value_json.position }}"}""",
        )
        assertEquals(Decoded.State(Cover(CoverState.OPEN, 60)), CoverMapper.decode(b, "z", """{"position":60}""", Unknown))
    }

    @Test
    fun `position without state derives open or closed`() {
        val b = bindings("cover", """{"position_topic":"p"}""")
        assertEquals(Decoded.State(Cover(CoverState.CLOSED, 0)), CoverMapper.decode(b, "p", "0", Unknown))
        assertEquals(Decoded.State(Cover(CoverState.OPEN, 60)), CoverMapper.decode(b, "p", "60", Unknown))
    }

    @Test
    fun `inverted position bounds`() {
        val b = bindings("cover", """{"position_topic":"p","set_position_topic":"p/set","position_open":0,"position_closed":100}""")
        assertEquals(Decoded.State(Cover(CoverState.OPEN, 75)), CoverMapper.decode(b, "p", "25", Unknown))
        assertEquals(publishes("p/set", "25"), CoverMapper.encode(b, Command.SetPosition(75), Unknown))
    }

    @Test
    fun `unrecognised state is ignored`() {
        val b = bindings("cover", """{"state_topic":"s"}""")
        assertEquals(Decoded.Ignored, CoverMapper.decode(b, "s", "wobbling", Unknown))
        assertEquals(Decoded.State(Cover(CoverState.CLOSING)), CoverMapper.decode(b, "s", "closing", Unknown))
    }

    @Test
    fun `commands`() {
        assertEquals(publishes("z/set", "OPEN"), CoverMapper.encode(z2m, Command.Open, Unknown))
        assertEquals(publishes("z/set", "CLOSE"), CoverMapper.encode(z2m, Command.Close, Unknown))
        assertEquals(publishes("z/set", "STOP"), CoverMapper.encode(z2m, Command.Stop, Unknown))
        assertEquals(publishes("z/set", """{ "position": 30 }"""), CoverMapper.encode(z2m, Command.SetPosition(30), Unknown))
        assertEquals(publishes("z/set", "OPEN"), CoverMapper.encode(z2m, Command.Toggle, Cover(CoverState.CLOSED, 0)))
        assertEquals(publishes("z/set", "CLOSE"), CoverMapper.encode(z2m, Command.Toggle, Cover(CoverState.OPEN, 100)))
    }

    @Test
    fun `prediction`() {
        assertEquals(Cover(CoverState.OPENING, 0), CoverMapper.predict(Command.Open, Cover(CoverState.CLOSED, 0)))
        assertEquals(Cover(CoverState.OPENING, 70), CoverMapper.predict(Command.SetPosition(70), Cover(CoverState.OPEN, 20)))
        assertEquals(setOf("z"), CoverMapper.stateTopics(z2m))
    }
}
