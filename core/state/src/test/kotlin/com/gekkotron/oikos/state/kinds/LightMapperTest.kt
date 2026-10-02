package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Hs
import com.gekkotron.oikos.model.StateValue.Light
import com.gekkotron.oikos.model.StateValue.Unknown
import com.gekkotron.oikos.state.bindings
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LightMapperTest {
    private val z2m = bindings(
        "light",
        """{"schema":"json","state_topic":"z/lampe","command_topic":"z/lampe/set","brightness":true,"brightness_scale":254}""",
    )
    /** The bedroom ZigLED strip, as Z2M 2.14.1 announces it. */
    private val zigled = bindings(
        "light",
        """{"schema":"json","state_topic":"z/leds","command_topic":"z/leds/set","brightness":true,"brightness_scale":254,
           "supported_color_modes":["xy"],"effect":true,"effect_list":["solid","breathe","fire_1d"]}""",
    )
    private fun withModes(vararg modes: String) = bindings(
        "light",
        """{"schema":"json","state_topic":"z/l","command_topic":"z/l/set","supported_color_modes":[${modes.joinToString(",") { "\"$it\"" }}]}""",
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
        assertEquals(publishes("z/lampe/set" to """{"state":"ON"}"""), LightMapper.encode(z2m, Command.TurnOn(color = Hs(0.0, 1.0)), Unknown))
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
        assertEquals(publishes("l/set" to "ON", "l/rgb/set" to "255,0,0"), LightMapper.encode(b, Command.TurnOn(color = Hs(0.0, 1.0)), Unknown))
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

    @Test
    fun `decodes the strip's state payload`() {
        val payload = """{"active_count":96,"brightness":0,"color":{"x":0.196,"y":0.203},"color_mode":"xy","effect":"fire_1d",
            "effect_intensity":39,"effect_speed":38,"palette":1,"state":"OFF"}"""
        val light = (LightMapper.decode(zigled, "z/leds", payload, Unknown) as Decoded.State).value as Light
        assertEquals(false, light.on)
        assertEquals(0, light.brightness)
        assertEquals("fire_1d", light.effect)
        val color = light.color!!
        assertTrue(abs(color.hue - 208.0) < 1.0 && abs(color.saturation - 0.78) < 0.01, "$color")
    }

    @Test
    fun `effect-only and color-only payloads are state updates`() {
        val prev = Light(true, 200, "solid", Hs(10.0, 1.0))
        assertEquals(Decoded.State(prev.copy(effect = "breathe")), LightMapper.decode(zigled, "z/leds", """{"effect":"breathe"}""", prev))
        val red = (LightMapper.decode(zigled, "z/leds", """{"color":{"r":255,"g":0,"b":0}}""", prev) as Decoded.State).value as Light
        assertEquals(Hs(0.0, 1.0), red.color)
        assertEquals("solid", red.effect)
    }

    @Test
    fun `a payload without effect or color keeps the previous ones`() {
        val prev = Light(true, 200, "solid", Hs(10.0, 1.0))
        assertEquals(Decoded.State(prev.copy(on = false)), LightMapper.decode(zigled, "z/leds", """{"state":"OFF","effect":null}""", prev))
    }

    @Test
    fun `hs payloads in either key style, wrapping at 360`() {
        val a = (LightMapper.decode(zigled, "z/leds", """{"color":{"h":120,"s":50}}""", Unknown) as Decoded.State).value as Light
        assertEquals(Hs(120.0, 0.5), a.color)
        val b = (LightMapper.decode(zigled, "z/leds", """{"color":{"hue":360,"saturation":100}}""", Unknown) as Decoded.State).value as Light
        assertEquals(Hs(0.0, 1.0), b.color)
    }

    @Test
    fun `effect and color are encoded in the light's own mode`() {
        assertEquals(
            publishes("z/leds/set" to """{"state":"ON","effect":"fire_1d"}"""),
            LightMapper.encode(zigled, Command.TurnOn(effect = "fire_1d"), Unknown),
        )
        assertEquals(
            publishes("z/leds/set" to """{"state":"ON","color":{"x":0.6401,"y":0.33}}"""),
            LightMapper.encode(zigled, Command.TurnOn(color = Hs(0.0, 1.0)), Unknown),
        )
        assertEquals(
            publishes("z/l/set" to """{"state":"ON","color":{"h":210.0,"s":80.0}}"""),
            LightMapper.encode(withModes("hs", "color_temp"), Command.TurnOn(color = Hs(210.0, 0.8)), Unknown),
        )
        assertEquals(
            publishes("z/l/set" to """{"state":"ON","color":{"r":255,"g":0,"b":0}}"""),
            LightMapper.encode(withModes("rgbw"), Command.TurnOn(color = Hs(0.0, 1.0)), Unknown),
        )
    }

    @Test
    fun `prediction overlays effect and color`() {
        val prev = Light(true, 200, "solid", Hs(10.0, 1.0))
        assertEquals(Light(true, 200, "comet", Hs(10.0, 1.0)), LightMapper.predict(Command.TurnOn(effect = "comet"), prev))
        assertEquals(Light(true, 200, "solid", Hs(90.0, 0.5)), LightMapper.predict(Command.TurnOn(color = Hs(90.0, 0.5)), prev))
        assertEquals(Light(false, 200, "solid", Hs(10.0, 1.0)), LightMapper.predict(Command.TurnOff, prev))
        assertNull(LightMapper.predict(Command.TurnOn(), Unknown).let { (it as Light).effect })
    }
}
