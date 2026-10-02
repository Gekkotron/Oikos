package com.gekkotron.oikos.demo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
}
