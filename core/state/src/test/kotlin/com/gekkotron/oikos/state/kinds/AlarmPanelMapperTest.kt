package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue.Alarm
import com.gekkotron.oikos.model.StateValue.Unknown
import com.gekkotron.oikos.state.bindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AlarmPanelMapperTest {
    private val plain = bindings("alarm_control_panel", """{"state_topic":"a","command_topic":"a/set"}""")

    private fun publishes(payload: String) = Encoded.Publish(listOf(Outgoing("a/set", payload, false, 0)))

    @Test
    fun `decodes standard states`() {
        assertEquals(Decoded.State(Alarm(AlarmState.ARMED_AWAY)), AlarmPanelMapper.decode(plain, "a", "armed_away", Unknown))
        assertEquals(Decoded.State(Alarm(AlarmState.TRIGGERED)), AlarmPanelMapper.decode(plain, "a", "triggered", Unknown))
        assertEquals(Decoded.Ignored, AlarmPanelMapper.decode(plain, "a", "weird", Unknown))
        val templated = bindings("alarm_control_panel", """{"state_topic":"a","command_topic":"a/set","value_template":"{{ value_json.state }}"}""")
        assertEquals(Decoded.State(Alarm(AlarmState.DISARMED)), AlarmPanelMapper.decode(templated, "a", """{"state":"disarmed"}""", Unknown))
    }

    @Test
    fun `default and custom action payloads`() {
        assertEquals(publishes("ARM_AWAY"), AlarmPanelMapper.encode(plain, Command.Arm(AlarmMode.AWAY), Unknown))
        assertEquals(publishes("ARM_CUSTOM_BYPASS"), AlarmPanelMapper.encode(plain, Command.Arm(AlarmMode.CUSTOM_BYPASS), Unknown))
        assertEquals(publishes("DISARM"), AlarmPanelMapper.encode(plain, Command.Disarm(), Unknown))
        val custom = bindings("alarm_control_panel", """{"command_topic":"a/set","payload_arm_night":"NUIT"}""")
        assertEquals(publishes("NUIT"), AlarmPanelMapper.encode(custom, Command.Arm(AlarmMode.NIGHT), Unknown))
    }

    @Test
    fun `supported features limit arm modes`() {
        val b = bindings("alarm_control_panel", """{"command_topic":"a/set","supported_features":["arm_home","arm_away"]}""")
        assertEquals(setOf(AlarmMode.HOME, AlarmMode.AWAY), AlarmPanelMapper.supportedModes(b))
        assertIs<Encoded.NotSupported>(AlarmPanelMapper.encode(b, Command.Arm(AlarmMode.NIGHT), Unknown))
        assertEquals(AlarmMode.entries.toSet(), AlarmPanelMapper.supportedModes(plain))
    }

    @Test
    fun `remote code is required and passed through the command template`() {
        val b = bindings(
            "alarm_control_panel",
            """{"command_topic":"a/set","code":"REMOTE_CODE","command_template":"{\"action\":\"{{ action }}\",\"code\":\"{{ code }}\"}"}""",
        )
        assertIs<Encoded.NotSupported>(AlarmPanelMapper.encode(b, Command.Arm(AlarmMode.AWAY), Unknown))
        assertEquals(
            publishes("""{"action":"ARM_AWAY","code":"1234"}"""),
            AlarmPanelMapper.encode(b, Command.Arm(AlarmMode.AWAY, "1234"), Unknown),
        )
        assertTrue(AlarmPanelMapper.codeRequired(b, arming = true))
    }

    @Test
    fun `fixed code is checked locally and can be optional for arming`() {
        val b = bindings("alarm_control_panel", """{"command_topic":"a/set","code":"0000","code_arm_required":false}""")
        assertEquals(publishes("ARM_HOME"), AlarmPanelMapper.encode(b, Command.Arm(AlarmMode.HOME), Unknown))
        assertIs<Encoded.NotSupported>(AlarmPanelMapper.encode(b, Command.Disarm("9999"), Unknown))
        assertEquals(publishes("DISARM"), AlarmPanelMapper.encode(b, Command.Disarm("0000"), Unknown))
        assertFalse(AlarmPanelMapper.codeRequired(plain, arming = true))
    }

    @Test
    fun `prediction`() {
        assertEquals(Alarm(AlarmState.ARMING), AlarmPanelMapper.predict(Command.Arm(AlarmMode.AWAY), Unknown))
        assertEquals(Alarm(AlarmState.DISARMING), AlarmPanelMapper.predict(Command.Disarm(), Unknown))
    }
}
