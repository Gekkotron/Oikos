package com.gekkotron.oikos.source.discovery

import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.Device
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.state.mqtt.MqttBindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryParserTest {
    private val prefix = "homeassistant"

    private fun fixture(name: String) = javaClass.getResource("/discovery/$name")!!.readText()

    private fun single(topic: String, payload: String): DiscoveredEntity {
        val r = DiscoveryParser.parse(prefix, topic, payload)
        assertIs<DiscoveryResult.Entities>(r, "expected entities, got $r")
        return r.items.single()
    }

    private fun reason(topic: String, payload: String) =
        (DiscoveryParser.parse(prefix, topic, payload) as DiscoveryResult.Skipped).reason

    private val DiscoveredEntity.b get() = entity.bindings as MqttBindings

    @Test
    fun `z2m json light`() {
        val d = single("homeassistant/light/0x00178801086a1b2c/light/config", fixture("z2m_light.json"))
        assertEquals("0x00178801086a1b2c_light_zigbee2mqtt", d.entity.uniqueId)
        assertEquals(Kind.LIGHT, d.entity.kind)
        assertEquals("lampe", d.entity.name)
        assertTrue(d.entity.capabilities.brightness)
        assertEquals(setOf("color_temp"), d.entity.capabilities.colorModes)
        assertEquals("zigbee2mqtt_0x00178801086a1b2c", d.device?.id)
        assertEquals("Philips", d.device?.manufacturer)
        assertEquals(d.device?.id, d.entity.deviceId)
        assertEquals("zigbee2mqtt/rooms/salon/lampe", d.b.str("state_topic"))
        assertEquals("salon", d.device?.pathArea)
    }

    @Test
    fun `z2m cover keeps position capability and suggested area`() {
        val d = single("homeassistant/cover/0xa4c138f00d1e2f3a/cover/config", fixture("z2m_cover.json"))
        assertEquals(Kind.COVER, d.entity.kind)
        assertEquals("shutter", d.entity.deviceClass)
        assertTrue(d.entity.capabilities.position)
        assertEquals("Salon", d.device?.suggestedArea)
    }

    // Review Focus 1
    @Test
    fun `contact sensor keeps boolean payload_on as Python string`() {
        val d = single("homeassistant/binary_sensor/0x00158d0004a1b2c3/contact/config", fixture("z2m_contact.json"))
        assertEquals(Kind.BINARY_SENSOR, d.entity.kind)
        assertEquals("door", d.entity.deviceClass)
        assertEquals("False", d.b.str("payload_on"))
        assertEquals("True", d.b.str("payload_off"))
        assertEquals("porte Door", d.entity.name)
    }

    @Test
    fun `motion and sensors`() {
        val motion = single("homeassistant/binary_sensor/0x00158d0005b2c3d4/occupancy/config", fixture("z2m_motion.json"))
        assertEquals("motion", motion.entity.deviceClass)
        val temp = single("homeassistant/sensor/0x00158d0006c3d4e5/temperature/config", fixture("z2m_temperature.json"))
        val hum = single("homeassistant/sensor/0x00158d0006c3d4e5/humidity/config", fixture("z2m_humidity.json"))
        assertEquals("°C", temp.entity.unit)
        assertEquals("capteur Temperature", temp.entity.name)
        assertEquals(temp.b.str("state_topic"), hum.b.str("state_topic"))
        assertEquals(temp.device, hum.device)
    }

    @Test
    fun `abbreviated config with base topic`() {
        val d = single("homeassistant/switch/ABC123/relay_1/config", fixture("tasmota_relay.json"))
        assertEquals("ABC123_RL_1", d.entity.uniqueId)
        assertEquals(Kind.SWITCH, d.entity.kind)
        assertEquals("tasmota_ABC123/stat/RESULT", d.b.str("state_topic"))
        assertEquals("tasmota_ABC123/cmnd/POWER", d.b.str("command_topic"))
        assertEquals("tasmota_ABC123/tele/LWT", d.b.str("availability_topic"))
        assertEquals("Online", d.b.str("payload_available"))
        assertEquals("{{value_json.POWER}}", d.b.str("value_template"))
        assertNull(d.b.config["~"])
        assertEquals(Device("ABC123", "Tasmota", "ITEAD", "Sonoff Basic", "Garage"), d.device)
        assertEquals("Tasmota Relay", d.entity.name)
    }

    @Test
    fun `base topic as suffix and inside availability list`() {
        val d = single(
            "homeassistant/switch/x/config",
            """{"~":"base","cmd_t":"cmd/~","avty":[{"t":"~/avail"}],"uniq_id":"u"}""",
        )
        assertEquals("cmd/base", d.b.str("command_topic"))
        assertEquals("""[{"topic":"base/avail"}]""", d.b.config["availability"].toString())
    }

    @Test
    fun `device-based discovery inherits shared options`() {
        val r = DiscoveryParser.parse(prefix, "homeassistant/device/ea334450945afc/config", fixture("device_based.json"))
        assertIs<DiscoveryResult.Entities>(r)
        assertEquals(listOf("temp01ae_t", "temp01ae_h"), r.items.map { it.entity.uniqueId })
        r.items.forEach {
            assertEquals("sensorBedroom/state", (it.entity.bindings as MqttBindings).str("state_topic"))
            assertEquals(2, (it.entity.bindings as MqttBindings).int("qos", 0))
            assertEquals("Kitchen", it.device?.name)
        }
        assertEquals("ea334450945afc", r.items.first().device?.id)
        assertEquals(emptyList(), r.skipped)
    }

    @Test
    fun `device-based discovery reports bad components but keeps good ones`() {
        val r = DiscoveryParser.parse(
            prefix, "homeassistant/device/d1/config",
            """{"dev":{"ids":"d1","name":"D"},"cmps":{"a":{"p":"sensor","stat_t":"s","uniq_id":"a1"},"b":{"p":"vacuum","uniq_id":"b1"}}}""",
        )
        assertIs<DiscoveryResult.Entities>(r)
        assertEquals(listOf("a1"), r.items.map { it.entity.uniqueId })
        assertEquals(1, r.skipped.size)
        assertTrue("vacuum" in r.skipped.single())
    }

    @Test
    fun `empty payload removes`() {
        assertEquals(DiscoveryResult.Removed, DiscoveryParser.parse(prefix, "homeassistant/light/a/b/config", ""))
    }

    @Test
    fun `non-config topics are not discovery`() {
        assertEquals(DiscoveryResult.NotDiscovery, DiscoveryParser.parse(prefix, "homeassistant/status", "online"))
        assertEquals(DiscoveryResult.NotDiscovery, DiscoveryParser.parse(prefix, "zigbee2mqtt/light/a/config", "{}"))
        assertEquals(DiscoveryResult.NotDiscovery, DiscoveryParser.parse(prefix, "homeassistant/a/b/c/d/config", "{}"))
        assertIs<DiscoveryResult.Entities>(
            DiscoveryParser.parse("custom", "custom/switch/x/config", """{"cmd_t":"c","uniq_id":"u"}"""),
        )
    }

    // Review Focus 3
    @Test
    fun `invalid JSON and non-object payloads are skipped`() {
        listOf("{not json", "[1,2,3]", "\"text\"", "{\"a\":").forEach { payload ->
            assertIs<DiscoveryResult.Skipped>(DiscoveryParser.parse(prefix, "homeassistant/switch/x/config", payload), payload)
        }
    }

    @Test
    fun `validation failures are skipped with a reason`() {
        fun reason(topic: String, payload: String) =
            (DiscoveryParser.parse(prefix, topic, payload) as DiscoveryResult.Skipped).reason
        assertTrue("vacuum" in reason("homeassistant/vacuum/x/config", """{"cmd_t":"c"}"""))
        assertTrue("command_topic" in reason("homeassistant/switch/x/config", """{"stat_t":"s"}"""))
        assertTrue("state_topic" in reason("homeassistant/sensor/x/config", """{"name":"n"}"""))
        assertTrue("options" in reason("homeassistant/select/x/config", """{"cmd_t":"c","options":[]}"""))
        assertTrue("template" in reason("homeassistant/light/x/config", """{"cmd_t":"c","schema":"template"}"""))
    }

    @Test
    fun `missing unique_id is derived from the topic`() {
        assertEquals("switch_node_obj", single("homeassistant/switch/node/obj/config", """{"cmd_t":"c"}""").entity.uniqueId)
        assertEquals("switch_obj", single("homeassistant/switch/obj/config", """{"cmd_t":"c"}""").entity.uniqueId)
    }

    @Test
    fun `names fall back to device then object id`() {
        assertEquals("obj", single("homeassistant/switch/obj/config", """{"cmd_t":"c"}""").entity.name)
        assertEquals(
            "Plug",
            single("homeassistant/switch/obj/config", """{"cmd_t":"c","name":null,"dev":{"ids":["p"],"name":"Plug"}}""").entity.name,
        )
        assertEquals(
            "Plug power",
            single("homeassistant/switch/obj/config", """{"cmd_t":"c","name":"Plug power","dev":{"ids":["p"],"name":"Plug"}}""").entity.name,
        )
    }

    @Test
    fun `devices without identifiers use connections or are dropped`() {
        val withConn = single(
            "homeassistant/switch/o/config",
            """{"cmd_t":"c","dev":{"cns":[["mac","aa:bb"]],"name":"M"}}""",
        )
        assertEquals("mac:aa:bb", withConn.device?.id)
        assertNull(single("homeassistant/switch/o/config", """{"cmd_t":"c","dev":{"name":"M"}}""").device)
    }

    @Test
    fun `capabilities per kind`() {
        val climate = single("homeassistant/climate/c/config", """{"modes":["off","heat"],"min_temp":10}""").entity.capabilities
        assertEquals(listOf("off", "heat"), climate.hvacModes)
        assertEquals(10.0, climate.minTemp)
        assertEquals(35.0, climate.maxTemp)
        val number = single("homeassistant/number/n/config", """{"cmd_t":"c","max":10,"step":0.5}""").entity.capabilities
        assertEquals(1.0, number.min)
        assertEquals(10.0, number.max)
        assertEquals(0.5, number.step)
        val select = single("homeassistant/select/s/config", """{"cmd_t":"c","ops":["a","b"]}""").entity.capabilities
        assertEquals(listOf("a", "b"), select.options)
        val alarm = single(
            "homeassistant/alarm_control_panel/a/config",
            """{"cmd_t":"c","code":"REMOTE_CODE","sup_feat":["arm_away"]}""",
        ).entity.capabilities
        assertEquals(setOf(AlarmMode.AWAY), alarm.alarmModes)
        assertTrue(alarm.codeArmRequired)
        val basicLight = single("homeassistant/light/l/config", """{"cmd_t":"c","bri_cmd_t":"b","rgb_cmd_t":"r"}""").entity.capabilities
        assertTrue(basicLight.brightness)
        assertEquals(setOf("rgb"), basicLight.colorModes)
    }

    @Test
    fun `diagnostic entities are hidden like Home Assistant`() {
        val r = reason("homeassistant/sensor/x/config", """{"stat_t":"s","entity_category":"diagnostic"}""")
        assertTrue("diagnostic" in r)
    }

    @Test
    fun `config entities are hidden like Home Assistant`() {
        val r = reason("homeassistant/number/x/config", """{"cmd_t":"c","entity_category":"config"}""")
        assertTrue("config" in r)
    }

    @Test
    fun `entities disabled by default are hidden`() {
        val r = reason("homeassistant/sensor/x/config", """{"stat_t":"s","enabled_by_default":false}""")
        assertTrue("disabled by default" in r)
    }

    @Test
    fun `abbreviated entity_category is expanded before hiding`() {
        val r = reason("homeassistant/sensor/x/config", """{"stat_t":"s","ent_cat":"diagnostic"}""")
        assertTrue("diagnostic" in r)
    }

    @Test
    fun `a normal z2m light is unaffected`() {
        val d = single("homeassistant/light/0x00178801086a1b2c/light/config", fixture("z2m_light.json"))
        assertEquals(Kind.LIGHT, d.entity.kind)
    }

    @Test
    fun `real z2m diagnostic linkquality sensor is hidden`() {
        val r = DiscoveryParser.parse(
            prefix,
            "homeassistant/sensor/0x403802fffeaffd2a/linkquality/config",
            fixture("z2m_linkquality_diagnostic.json"),
        )
        assertIs<DiscoveryResult.Skipped>(r)
        assertTrue("diagnostic" in r.reason)
    }

    @Test
    fun `device-based discovery hides diagnostic components but keeps sensors`() {
        val r = DiscoveryParser.parse(
            prefix, "homeassistant/device/d2/config",
            """{"dev":{"ids":"d2","name":"D"},"cmps":{"a":{"p":"sensor","stat_t":"s","uniq_id":"a1"},"b":{"p":"sensor","stat_t":"s","uniq_id":"b1","entity_category":"diagnostic"}}}""",
        )
        assertIs<DiscoveryResult.Entities>(r)
        assertEquals(listOf("a1"), r.items.map { it.entity.uniqueId })
        assertEquals(1, r.skipped.size)
        assertTrue("hidden" in r.skipped.single() && "diagnostic" in r.skipped.single())
    }

    @Test
    fun `hierarchical device names split into a room and a short name, short paths are left alone`() {
        assertEquals(DevicePath.Parts("lampe", "julien"), DevicePath.split("rooms/julien/lampe"))
        assertEquals(DevicePath.Parts("plafond", "salon"), DevicePath.split("maison/rdc/salon/plafond"))
        assertEquals(DevicePath.Parts("AC/DC plug", null), DevicePath.split("AC/DC plug"))
        assertEquals(DevicePath.Parts("a//b", null), DevicePath.split("a//b"))
        assertEquals(DevicePath.Parts("Lampe", null), DevicePath.split("Lampe"))
    }

    @Test
    fun `an entity name that repeats the full device path is shortened too`() {
        val config = """{"command_topic":"z/p/set","state_topic":"z/p","unique_id":"p_power","name":"rooms/julien/prise Power",
            "device":{"identifiers":["p"],"name":"rooms/julien/prise"}}"""
        assertEquals("prise Power", single("homeassistant/switch/p/power/config", config).entity.name)
        assertEquals("prise" to "Power", single("homeassistant/switch/p/power/config", config).entity.let { it.deviceName to it.label })
    }

    @Test
    fun `the device name and the entity's own label are kept apart for translation`() {
        fun parts(config: String) = single("homeassistant/switch/obj/config", config).entity.let { it.deviceName to it.label }
        val temp = single("homeassistant/sensor/0x00158d0006c3d4e5/temperature/config", fixture("z2m_temperature.json")).entity
        assertEquals("capteur" to "Temperature", temp.deviceName to temp.label)
        val light = single("homeassistant/light/0x00178801086a1b2c/light/config", fixture("z2m_light.json")).entity
        assertEquals("lampe" to null, light.deviceName to light.label)
        assertEquals("Plug" to "power", parts("""{"cmd_t":"c","name":"Plug power","dev":{"ids":["p"],"name":"Plug"}}"""))
        assertEquals("Plug" to null, parts("""{"cmd_t":"c","name":"Plug","dev":{"ids":["p"],"name":"Plug"}}"""))
        // A name that only happens to start with the device name is one custom name, not device + label.
        assertEquals(null to null, parts("""{"cmd_t":"c","name":"Plugin light","dev":{"ids":["p"],"name":"Plug"}}"""))
        assertEquals(null to null, parts("""{"cmd_t":"c","name":"Relay"}"""))
    }
}
