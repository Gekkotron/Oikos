package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.StateValue.Light
import com.gekkotron.oikos.model.StateValue.Numeric
import com.gekkotron.oikos.model.StateValue.OnOff
import com.gekkotron.oikos.model.StateValue.Unknown
import com.gekkotron.oikos.state.bindings
import kotlin.test.Test
import kotlin.test.assertEquals

/** Discovery configs published by the Philips TV bridge, decoded against its status payload. */
class PhilipsTemplatesTest {
    private val full =
        """{"state":1,"volume":25,"muted":false,"ambilight_power":"On","ambilight_brightness":7,"ambilight_mode":"FOLLOW_VIDEO"}"""
    private val nulls =
        """{"state":0,"volume":null,"muted":null,"ambilight_power":"Off","ambilight_brightness":null,"ambilight_mode":null}"""

    private val power = bindings(
        "switch",
        """{"state_topic":"philips/status","value_template":"{{ 'ON' if value_json.state == 1 else 'OFF' }}","state_on":"ON","state_off":"OFF",
           "command_topic":"philips/cmd/power","payload_on":"ON","payload_off":"OFF"}""",
    )
    private val muted = bindings(
        "switch",
        """{"state_topic":"philips/status","value_template":"{{ 'ON' if value_json.muted else 'OFF' }}","command_topic":"philips/cmd/mute"}""",
    )
    private val volume = bindings(
        "number",
        """{"state_topic":"philips/status","value_template":"{% if value_json.volume is not none %}{{ value_json.volume }}{% endif %}",
           "command_topic":"philips/cmd/volume","min":0,"max":60,"step":1,"mode":"slider"}""",
    )
    private val ambilight = bindings(
        "light",
        """{"state_topic":"philips/status","state_value_template":"{{ 'ON' if value_json.ambilight_power == 'On' else 'OFF' }}",
           "brightness_state_topic":"philips/status","brightness_value_template":"{{ value_json.ambilight_brightness }}",
           "effect_state_topic":"philips/status","effect_value_template":"{{ value_json.ambilight_mode or '' }}",
           "command_topic":"philips/cmd/ambilight/power","payload_on":"ON","payload_off":"OFF",
           "brightness_command_topic":"philips/cmd/ambilight/brightness","brightness_scale":10,
           "effect_command_topic":"philips/cmd/ambilight/mode","effect_list":["FOLLOW_VIDEO","FOLLOW_AUDIO","FOLLOW_COLOR"]}""",
    )

    @Test
    fun `full payload decodes every entity`() {
        assertEquals(Decoded.State(OnOff(true)), SwitchMapper.decode(power, "philips/status", full, Unknown))
        assertEquals(Decoded.State(OnOff(false)), SwitchMapper.decode(muted, "philips/status", full, Unknown))
        assertEquals(Decoded.State(Numeric(25.0)), NumberMapper.decode(volume, "philips/status", full, Unknown))
        assertEquals(
            Decoded.State(Light(true, 179, "FOLLOW_VIDEO")),
            LightMapper.decode(ambilight, "philips/status", full, Unknown),
        )
    }

    @Test
    fun `null fields are ignored, never unsupported or an error`() {
        assertEquals(Decoded.State(OnOff(false)), SwitchMapper.decode(power, "philips/status", nulls, Unknown))
        assertEquals(Decoded.State(OnOff(false)), SwitchMapper.decode(muted, "philips/status", nulls, Unknown))
        assertEquals(Decoded.Ignored, NumberMapper.decode(volume, "philips/status", nulls, Unknown))
        assertEquals(
            Decoded.State(Light(false)),
            LightMapper.decode(ambilight, "philips/status", nulls, Unknown),
        )
    }

    @Test
    fun `null effect and brightness keep the previous light values`() {
        assertEquals(
            Decoded.State(Light(false, 128, "FOLLOW_AUDIO")),
            LightMapper.decode(ambilight, "philips/status", nulls, Light(true, 128, "FOLLOW_AUDIO")),
        )
    }
}
