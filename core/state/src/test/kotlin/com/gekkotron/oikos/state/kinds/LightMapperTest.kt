package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Rgb
import com.gekkotron.oikos.model.StateValue.Light
import com.gekkotron.oikos.model.StateValue.Unknown
import com.gekkotron.oikos.state.bindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LightMapperTest {
    private val z2m = bindings(
        "light",
        """{"schema":"json","state_topic":"z/lampe","command_topic":"z/lampe/set","brightness":true,"brightness_scale":254}""",
    )
    private val basic = bindings(
        "light",
        """{"state_topic":"l/state","command_topic":"l/set","brightness_state_topic":"l/bri","brightness_command_topic":"l/bri/set"}""",
    )

    private fun publishes(vararg pairs: Pair<String, String>) =
        Encoded.Publish(pairs.map { Outgoing(it.first, it.second, false, 0) })

    @Test
    fun `json schema decodes state and scales brightness`() {
        assertEquals(
            Decoded.State(Light(true, 128)),
            LightMapper.decode(z2m, "z/lampe", """{"state":"ON","brightness":127,"linkquality":80}""", Unknown),
        )
    }

    @Test
    fun `json schema OFF keeps the previous brightness`() {
        assertEquals(Decoded.State(Light(false, 200)), LightMapper.decode(z2m, "z/lampe", """{"state":"OFF"}""", Light(true, 200)))
    }

    @Test
    fun `json schema ignores payloads without state or brightness and rejects non-JSON`() {
        assertEquals(Decoded.Ignored, LightMapper.decode(z2m, "z/lampe", """{"linkquality":80}""", Unknown))
        assertIs<Decoded.Error>(LightMapper.decode(z2m, "z/lampe", "ON", Unknown))
    }

    @Test
    fun `json schema commands`() {
        assertEquals(publishes("z/lampe/set" to """{"state":"ON","brightness":254}"""), LightMapper.encode(z2m, Command.TurnOn(brightness = 255), Unknown))
        assertEquals(
            publishes("z/lampe/set" to """{"state":"ON","color":{"r":255,"g":0,"b":10}}"""),
            LightMapper.encode(z2m, Command.TurnOn(color = Rgb(255, 0, 10)), Unknown),
        )
        assertEquals(publishes("z/lampe/set" to """{"state":"OFF"}"""), LightMapper.encode(z2m, Command.TurnOff, Light(true, 10)))
    }

    @Test
    fun `default schema decodes state and brightness topics separately`() {
        assertEquals(Decoded.State(Light(true, null)), LightMapper.decode(basic, "l/state", "ON", Unknown))
        assertEquals(Decoded.State(Light(true, 128)), LightMapper.decode(basic, "l/bri", "128", Light(true, null)))
        assertEquals(Decoded.Ignored, LightMapper.decode(basic, "l/state", "DIM", Unknown))
    }

    @Test
    fun `default schema turn on with brightness sends both`() {
        assertEquals(publishes("l/set" to "ON", "l/bri/set" to "128"), LightMapper.encode(basic, Command.TurnOn(brightness = 128), Unknown))
    }

    @Test
    fun `on_command_type brightness sends only brightness`() {
        val b = bindings("light", """{"command_topic":"l/set","brightness_command_topic":"l/bri/set","on_command_type":"brightness"}""")
        assertEquals(publishes("l/bri/set" to "128"), LightMapper.encode(b, Command.TurnOn(brightness = 128), Unknown))
        assertEquals(publishes("l/set" to "ON"), LightMapper.encode(b, Command.TurnOn(), Unknown))
    }

    @Test
    fun `default schema rgb and toggle`() {
        val b = bindings("light", """{"command_topic":"l/set","rgb_command_topic":"l/rgb/set"}""")
        assertEquals(publishes("l/set" to "ON", "l/rgb/set" to "1,2,3"), LightMapper.encode(b, Command.TurnOn(color = Rgb(1, 2, 3)), Unknown))
        assertEquals(publishes("l/set" to "OFF"), LightMapper.encode(b, Command.Toggle, Light(true, null)))
    }

    @Test
    fun `state topics and prediction`() {
        assertEquals(setOf("l/state", "l/bri"), LightMapper.stateTopics(basic))
        assertEquals(setOf("z/lampe"), LightMapper.stateTopics(z2m))
        assertEquals(Light(true, 100), LightMapper.predict(Command.TurnOn(brightness = 100), Unknown))
        assertEquals(Light(false, 100), LightMapper.predict(Command.Toggle, Light(true, 100)))
        assertIs<Encoded.NotSupported>(LightMapper.encode(basic, Command.SetPosition(1), Unknown))
    }
}
