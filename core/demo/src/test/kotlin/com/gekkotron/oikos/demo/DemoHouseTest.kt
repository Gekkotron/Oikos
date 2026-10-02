package com.gekkotron.oikos.demo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DemoHouseTest {
    private val strip = "demo/bedroom/strip"

    @Test
    fun `the LED strip announces effects, a colour mode and two numbers on its device`() {
        val configs = DemoHouse().retainedMessages().filter { it.topic.endsWith("/config") }
        val light = configs.single { it.topic == "homeassistant/light/oikos_demo/demo_bedroom_strip/config" }
        assertTrue(""""effect_list":["solid","breathe","rainbow","candle"]""" in light.payload, light.payload)
        assertTrue(""""supported_color_modes":["xy"]""" in light.payload, light.payload)
        val numbers = configs.filter { it.topic.startsWith("homeassistant/number/oikos_demo/demo_bedroom_strip_") }
        assertEquals(2, numbers.size)
        assertTrue(numbers.all { "\"demo_led_strip\"" in it.payload })
    }

    @Test
    fun `strip commands are echoed into its state document`() {
        val house = DemoHouse()
        val speed = assertNotNull(house.onCommand("$strip/set/effect_speed", "40"))
        assertEquals(40, Json.parseToJsonElement(speed.payload).jsonObject.getValue("effect_speed").jsonPrimitive.int)
        val fx = assertNotNull(house.onCommand("$strip/set", """{"state":"ON","effect":"candle"}"""))
        val doc = Json.parseToJsonElement(fx.payload).jsonObject
        assertEquals(JsonPrimitive("candle"), doc["effect"])
        assertEquals(40, doc.getValue("effect_speed").jsonPrimitive.int)
    }

    private fun alarmState(house: DemoHouse): String? = house.retainedMessages()
        .single { it.topic == "demo/alarm" }
        .let { Json.parseToJsonElement(it.payload).jsonObject["state"]?.jsonPrimitive?.contentOrNull }

    @Test
    fun `the alarm takes a remote code for disarming only`() {
        val config = DemoHouse().retainedMessages().single { it.topic == "homeassistant/alarm_control_panel/oikos_demo/demo_alarm/config" }.payload
        assertTrue(""""code":"REMOTE_CODE"""" in config, config)
        assertTrue(""""code_arm_required":false""" in config, config)
        assertTrue(""""code_disarm_required":true""" in config, config)
    }

    @Test
    fun `arming goes through the exit delay`() {
        val house = DemoHouse()
        assertNotNull(house.onCommand("demo/alarm/set", """{"action":"ARM_AWAY","code":""}"""))
        assertEquals("arming", alarmState(house))
        assertTrue(house.tick().any { it.topic == "demo/alarm" })
        assertEquals("armed_away", alarmState(house))
    }

    @Test
    fun `disarm needs the right code, a wrong one republishes the state`() {
        val house = DemoHouse()
        house.onCommand("demo/alarm/set", """{"action":"ARM_HOME","code":""}""")
        house.tick()
        val wrong = assertNotNull(house.onCommand("demo/alarm/set", """{"action":"DISARM","code":"0000"}"""))
        assertEquals("armed_home", Json.parseToJsonElement(wrong.payload).jsonObject.getValue("state").jsonPrimitive.contentOrNull)
        house.onCommand("demo/alarm/set", """{"action":"DISARM","code":"1234"}""")
        assertEquals("disarmed", alarmState(house))
    }

    @Test
    fun `an intrusion only trips an armed alarm`() {
        val house = DemoHouse()
        assertNull(house.onCommand("demo/alarm/intrude", "PRESS"))
        assertEquals("disarmed", alarmState(house))
        house.onCommand("demo/alarm/set", """{"action":"ARM_NIGHT","code":""}""")
        house.tick()
        house.onCommand("demo/alarm/intrude", "PRESS")
        assertEquals("pending", alarmState(house))
        house.tick()
        assertEquals("triggered", alarmState(house))
    }

    @Test
    fun `the security tab has one sensor per main class`() {
        val configs = DemoHouse().retainedMessages().filter { it.topic.startsWith("homeassistant/binary_sensor/") }.map { it.payload }
        for (c in listOf("door", "window", "motion", "smoke", "moisture")) {
            assertTrue(configs.any { """"device_class":"$c"""" in it }, c)
        }
    }
}
