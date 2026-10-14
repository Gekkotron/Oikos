package com.gekkotron.oikos.presence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresencePayloadsTest {
    @Test
    fun `topics are HA MQTT discovery conventional`() {
        assertEquals("homeassistant/device_tracker/oikos_pixel/config", PresenceTopics.discovery("pixel"))
        assertEquals("oikos/presence/pixel/state", PresenceTopics.state("pixel"))
        assertEquals("oikos/presence/pixel/available", PresenceTopics.availability("pixel"))
    }

    @Test
    fun `aliases are slugified for HA entity ids`() {
        assertEquals("homeassistant/device_tracker/oikos_pixel_9/config", PresenceTopics.discovery("Pixel 9"))
        assertEquals("oikos/presence/pixel_9/state", PresenceTopics.state("Pixel 9"))
    }

    @Test
    fun `state payload is home or not_home`() {
        assertEquals("home", PresencePayloads.state(PresenceState.HOME))
        assertEquals("not_home", PresencePayloads.state(PresenceState.AWAY))
    }

    @Test
    fun `availability payload is online or offline`() {
        assertEquals("online", PresencePayloads.AVAILABLE_ONLINE)
        assertEquals("offline", PresencePayloads.AVAILABLE_OFFLINE)
    }

    @Test
    fun `discovery payload declares the device_tracker and wires topics by alias`() {
        val json = Json.parseToJsonElement(PresencePayloads.discovery("Pixel 9")).jsonObject
        assertEquals("Oikos Pixel 9", json.string("name"))
        assertEquals("oikos/presence/pixel_9/state", json.string("state_topic"))
        assertEquals("oikos/presence/pixel_9/available", json.string("availability_topic"))
        assertEquals("home", json.string("payload_home"))
        assertEquals("not_home", json.string("payload_not_home"))
        assertEquals("gps", json.string("source_type"))
        assertEquals("oikos_presence_pixel_9", json.string("unique_id"))
        val device = json["device"] as JsonObject
        assertEquals("Oikos Pixel 9", device.string("name"))
        assertEquals("Oikos", device.string("manufacturer"))
        assertEquals("Android", device.string("model"))
    }

    @Test
    fun `discovery payload is deterministic for the same alias`() {
        assertEquals(PresencePayloads.discovery("pixel"), PresencePayloads.discovery("pixel"))
    }

    @Test
    fun `an empty alias falls back to a safe default`() {
        assertTrue(PresenceTopics.state("").contains("oikos"))
        assertEquals("oikos/presence/phone/state", PresenceTopics.state(""))
    }

    private fun JsonObject.string(name: String): String = this[name]!!.jsonPrimitive.content
    private val kotlinx.serialization.json.JsonElement.jsonObject get() = this as JsonObject
}
