package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue.Unknown
import com.gekkotron.oikos.model.StateValue.Vacuum
import com.gekkotron.oikos.state.bindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class VacuumMapperTest {
    // What Roborock2Mqtt publishes for the S5.
    private val roborock = bindings(
        "vacuum",
        """{"state_topic":"roborock/ha/state","command_topic":"roborock/ha/command",
           "set_fan_speed_topic":"roborock/ha/fan_speed/set",
           "fan_speed_list":["Silent","Standard","Medium","Turbo","Gentle","Auto"],
           "supported_features":["start","pause","stop","return_home","locate","fan_speed","battery","status"]}""",
    )

    private fun publishes(topic: String, payload: String) = Encoded.Publish(listOf(Outgoing(topic, payload, false, 0)))

    @Test
    fun `full state`() {
        assertEquals(
            Decoded.State(Vacuum("docked", 100, "Turbo")),
            VacuumMapper.decode(roborock, "roborock/ha/state", """{"state":"docked","battery_level":100,"fan_speed":"Turbo","error_code":0}""", Unknown),
        )
    }

    @Test
    fun `partial state keeps previous values`() {
        assertEquals(
            Decoded.State(Vacuum("cleaning", 80, "Turbo")),
            VacuumMapper.decode(roborock, "roborock/ha/state", """{"state":"cleaning"}""", Vacuum("docked", 80, "Turbo")),
        )
    }

    @Test
    fun `explicit null fan speed clears it`() {
        assertEquals(
            Decoded.State(Vacuum("docked", 100, null)),
            VacuumMapper.decode(roborock, "roborock/ha/state", """{"state":"docked","battery_level":100,"fan_speed":null}""", Vacuum("docked", 100, "Turbo")),
        )
    }

    @Test
    fun `odd values`() {
        assertEquals(
            Decoded.State(Vacuum("docked", 100, null)),
            VacuumMapper.decode(roborock, "roborock/ha/state", """{"state":"unknown","battery_level":99.6}""", Vacuum("docked", 50, null)),
        )
        assertIs<Decoded.Error>(VacuumMapper.decode(roborock, "roborock/ha/state", "docked", Unknown))
        assertEquals(Decoded.Ignored, VacuumMapper.decode(roborock, "other", "{}", Unknown))
    }

    @Test
    fun `actions publish plain payloads`() {
        assertEquals(publishes("roborock/ha/command", "start"), VacuumMapper.encode(roborock, Command.VacuumAction("start"), Unknown))
        assertEquals(publishes("roborock/ha/command", "return_to_base"), VacuumMapper.encode(roborock, Command.VacuumAction("return_to_base"), Unknown))
        assertEquals(publishes("roborock/ha/command", "locate"), VacuumMapper.encode(roborock, Command.VacuumAction("locate"), Unknown))
        assertEquals(publishes("roborock/ha/command", "return_to_base"), VacuumMapper.encode(roborock, Command.TurnOff, Unknown))
        assertIs<Encoded.NotSupported>(VacuumMapper.encode(roborock, Command.VacuumAction("clean_spot"), Unknown))
    }

    @Test
    fun `payload overrides and unsupported actions`() {
        val custom = bindings(
            "vacuum",
            """{"state_topic":"s","command_topic":"c","payload_return_to_base":"dock","supported_features":["start","return_home"]}""",
        )
        assertEquals(publishes("c", "dock"), VacuumMapper.encode(custom, Command.VacuumAction("return_to_base"), Unknown))
        assertIs<Encoded.NotSupported>(VacuumMapper.encode(custom, Command.VacuumAction("pause"), Unknown))
        val noCommandTopic = bindings("vacuum", """{"state_topic":"s"}""")
        assertIs<Encoded.NotSupported>(VacuumMapper.encode(noCommandTopic, Command.VacuumAction("start"), Unknown))
    }

    @Test
    fun `fan speed`() {
        assertEquals(publishes("roborock/ha/fan_speed/set", "Silent"), VacuumMapper.encode(roborock, Command.SetFanSpeed("Silent"), Unknown))
        assertIs<Encoded.NotSupported>(VacuumMapper.encode(roborock, Command.SetFanSpeed("Max"), Unknown))
    }

    @Test
    fun `prediction and topics`() {
        val docked = Vacuum("docked", 100, "Turbo")
        assertEquals(Vacuum("cleaning", 100, "Turbo"), VacuumMapper.predict(Command.VacuumAction("start"), docked))
        assertEquals(Vacuum("paused", 100, "Turbo"), VacuumMapper.predict(Command.VacuumAction("pause"), docked))
        assertEquals(Vacuum("idle", 100, "Turbo"), VacuumMapper.predict(Command.VacuumAction("stop"), docked))
        assertEquals(Vacuum("returning", 100, "Turbo"), VacuumMapper.predict(Command.VacuumAction("return_to_base"), docked))
        assertNull(VacuumMapper.predict(Command.VacuumAction("locate"), docked))
        assertEquals(Vacuum("docked", 100, "Silent"), VacuumMapper.predict(Command.SetFanSpeed("Silent"), docked))
        assertEquals(setOf("roborock/ha/state"), VacuumMapper.stateTopics(roborock))
        assertEquals(setOf("roborock/ha/state"), VacuumMapper.feedbackTopics(roborock, Command.VacuumAction("start")))
        assertEquals(emptySet(), VacuumMapper.feedbackTopics(roborock, Command.VacuumAction("locate")))
    }
}
