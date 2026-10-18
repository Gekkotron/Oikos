package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue.OnOff
import com.gekkotron.oikos.model.StateValue.Unknown
import com.gekkotron.oikos.state.bindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SwitchMapperTest {
    private val plain = bindings("switch", """{"state_topic":"s","command_topic":"c"}""")

    @Test
    fun `default payloads decode`() {
        assertEquals(Decoded.State(OnOff(true)), SwitchMapper.decode(plain, "s", "ON", Unknown))
        assertEquals(Decoded.State(OnOff(false)), SwitchMapper.decode(plain, "s", "OFF", Unknown))
        assertEquals(Decoded.Ignored, SwitchMapper.decode(plain, "s", "MAYBE", Unknown))
    }

    @Test
    fun `value_template and state_on override the payloads`() {
        val b = bindings(
            "switch",
            """{"state_topic":"s","command_topic":"c","value_template":"{{ value_json.state }}","payload_on":"1","payload_off":"0","state_on":"on","state_off":"off"}""",
        )
        assertEquals(Decoded.State(OnOff(true)), SwitchMapper.decode(b, "s", """{"state":"on"}""", Unknown))
        assertEquals(Decoded.State(OnOff(false)), SwitchMapper.decode(b, "s", """{"state":"off"}""", Unknown))
    }

    @Test
    fun `zigbee2mqtt boolean switch with an if-else template decodes`() {
        val b = bindings(
            "switch",
            """{"state_topic":"s","command_topic":"c","payload_on":"true","payload_off":"false","value_template":"{% if value_json['alarm'] %}true{% else %}false{% endif %}"}""",
        )
        assertEquals(Decoded.State(OnOff(false)), SwitchMapper.decode(b, "s", """{"alarm":false,"battery":100}""", Unknown))
        assertEquals(Decoded.State(OnOff(true)), SwitchMapper.decode(b, "s", """{"alarm":true}""", Unknown))
    }

    @Test
    fun `commands use payloads, retain and qos`() {
        val b = bindings("switch", """{"command_topic":"c","payload_on":"1","payload_off":"0","retain":true,"qos":1}""")
        assertEquals(Encoded.Publish(listOf(Outgoing("c", "1", true, 1))), SwitchMapper.encode(b, Command.TurnOn(), Unknown))
        assertEquals(Encoded.Publish(listOf(Outgoing("c", "0", true, 1))), SwitchMapper.encode(b, Command.TurnOff, Unknown))
    }

    @Test
    fun `toggle flips the previous state`() {
        assertEquals(Encoded.Publish(listOf(Outgoing("c", "OFF", false, 0))), SwitchMapper.encode(plain, Command.Toggle, OnOff(true)))
        assertEquals(Encoded.Publish(listOf(Outgoing("c", "ON", false, 0))), SwitchMapper.encode(plain, Command.Toggle, Unknown))
    }

    @Test
    fun `command_template renders value`() {
        val b = bindings("switch", """{"command_topic":"c","command_template":"{\"state\": \"{{ value }}\"}"}""")
        assertEquals(Encoded.Publish(listOf(Outgoing("c", """{"state": "ON"}""", false, 0))), SwitchMapper.encode(b, Command.TurnOn(), Unknown))
    }

    @Test
    fun `unsupported command is rejected`() {
        assertIs<Encoded.NotSupported>(SwitchMapper.encode(plain, Command.SetNumber(1.0), Unknown))
    }

    @Test
    fun `predict and state topics`() {
        assertEquals(OnOff(true), SwitchMapper.predict(Command.TurnOn(), Unknown))
        assertEquals(OnOff(false), SwitchMapper.predict(Command.Toggle, OnOff(true)))
        assertEquals(setOf("s"), SwitchMapper.stateTopics(plain))
        assertEquals(emptySet(), SwitchMapper.stateTopics(bindings("switch", """{"command_topic":"c"}""")))
    }
}
