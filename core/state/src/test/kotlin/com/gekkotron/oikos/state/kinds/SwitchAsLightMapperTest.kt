package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue.Light
import com.gekkotron.oikos.model.StateValue.OnOff
import com.gekkotron.oikos.model.StateValue.Unknown
import com.gekkotron.oikos.state.bindings
import com.gekkotron.oikos.state.testEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SwitchAsLightMapperTest {
    private val b = bindings("switch", """{"state_topic":"s","command_topic":"c"}""")

    @Test
    fun `switch payloads decode to a light state`() {
        assertEquals(Decoded.State(Light(true)), SwitchAsLightMapper.decode(b, "s", "ON", Unknown))
        assertEquals(Decoded.State(Light(false)), SwitchAsLightMapper.decode(b, "s", "OFF", Light(true)))
        assertEquals(Decoded.Ignored, SwitchAsLightMapper.decode(b, "s", "MAYBE", Unknown))
    }

    @Test
    fun `toggle reads a light previous state and publishes switch payloads`() {
        assertEquals(Encoded.Publish(listOf(Outgoing("c", "OFF", false, 0))), SwitchAsLightMapper.encode(b, Command.Toggle, Light(true)))
        assertEquals(Encoded.Publish(listOf(Outgoing("c", "ON", false, 0))), SwitchAsLightMapper.encode(b, Command.TurnOn(brightness = 10), Unknown))
    }

    @Test
    fun `predict yields a light and state topics come from the switch`() {
        assertEquals(Light(true), SwitchAsLightMapper.predict(Command.TurnOn(), Unknown))
        assertEquals(Light(false), SwitchAsLightMapper.predict(Command.Toggle, Light(true)))
        assertEquals(setOf("s"), SwitchAsLightMapper.stateTopics(b))
    }

    @Test
    fun `only a switch shown as a light gets this mapper`() {
        val asLight = testEntity("a", Kind.LIGHT, "switch", "{}").copy(protocolKind = Kind.SWITCH)
        assertSame(SwitchAsLightMapper, KindMappers.forEntity(asLight))
        assertSame(SwitchMapper, KindMappers.forEntity(testEntity("a", Kind.SWITCH, "switch", "{}")))
        assertSame(LightMapper, KindMappers.forEntity(testEntity("a", Kind.LIGHT, "light", "{}")))
    }
}
