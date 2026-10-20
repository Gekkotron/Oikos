package com.gekkotron.oikos.source.discovery

import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.CodeFormat
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
    fun `WaterHeaterController device discovery parses into one device`() {
        val r = DiscoveryParser.parse(prefix, "homeassistant/device/water_heater/config", fixture("water_heater_controller.json"))
        assertIs<DiscoveryResult.Entities>(r)
        // Diagnostic entities stay hidden, as for every other device.
        assertEquals(listOf("temp_1", "frequency", "frequency_error"), r.skipped.map { it.substringBefore(':') })
        val byId = r.items.associateBy { it.entity.uniqueId }
        assertEquals(
            mapOf(
                "water_heater" to Kind.WATER_HEATER,
                "water_heater_tank_temp" to Kind.SENSOR,
                "water_heater_power_0" to Kind.NUMBER,
                "water_heater_power_1" to Kind.NUMBER,
                "water_heater_power_2" to Kind.NUMBER,
                "water_heater_temp_0" to Kind.SENSOR,
            ),
            byId.mapValues { it.value.entity.kind },
        )
        assertEquals(setOf("water_heater_controller"), r.items.map { it.entity.deviceId }.toSet())
        val heater = byId.getValue("water_heater")
        assertEquals(emptyList(), heater.entity.capabilities.hvacModes)
        assertEquals("water-heater/data", heater.b.str("json_attributes_topic"))
        assertTrue(heater.b.str("json_attributes_template").orEmpty().contains("tojson"))
        assertEquals("water-heater/data", heater.b.str("mode_state_topic"))
    }

    @Test
    fun `device-based discovery reports bad components but keeps good ones`() {
        val r = DiscoveryParser.parse(
            prefix, "homeassistant/device/d1/config",
            """{"dev":{"ids":"d1","name":"D"},"cmps":{"a":{"p":"sensor","stat_t":"s","uniq_id":"a1"},"b":{"p":"lawn_mower","uniq_id":"b1"}}}""",
        )
        assertIs<DiscoveryResult.Entities>(r)
        assertEquals(listOf("a1"), r.items.map { it.entity.uniqueId })
        assertEquals(1, r.skipped.size)
        assertTrue("lawn_mower" in r.skipped.single())
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
        assertTrue("lawn_mower" in reason("homeassistant/lawn_mower/x/config", """{"cmd_t":"c"}"""))
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
        assertEquals(emptyList(), climate.fanModes)
        val fan = single("homeassistant/climate/f/config", """{"fan_mode_command_topic":"f","fan_modes":["auto","1","2"]}""").entity.capabilities
        assertEquals(listOf("auto", "1", "2"), fan.fanModes)
        val fanDefault = single("homeassistant/climate/g/config", """{"fan_mode_command_topic":"f"}""").entity.capabilities
        assertEquals(listOf("auto", "low", "medium", "high"), fanDefault.fanModes)
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
        val vacuum = single(
            "homeassistant/vacuum/v/config",
            """{"stat_t":"s","cmd_t":"c","set_fan_spd_t":"f","fanspd_lst":["Silent","Turbo"],
               "sup_feat":["start","pause","return_home","locate","fan_speed","battery","status"]}""",
        ).entity
        assertEquals(Kind.VACUUM, vacuum.kind)
        assertEquals(listOf("start", "pause", "return_to_base", "locate"), vacuum.capabilities.vacuumActions)
        assertEquals(listOf("Silent", "Turbo"), vacuum.capabilities.fanSpeeds)
        // HA defaults: start, stop, return_home, status, battery, clean_spot; no fan_speed feature → no speeds
        val vacuumDefault = single("homeassistant/vacuum/w/config", """{"stat_t":"s","cmd_t":"c","set_fan_spd_t":"f","fanspd_lst":["Silent"]}""").entity.capabilities
        assertEquals(listOf("start", "stop", "return_to_base"), vacuumDefault.vacuumActions)
        assertEquals(emptyList(), vacuumDefault.fanSpeeds)
        // fan_speed supported but no set_fan_speed_topic → no speeds
        val noFanTopic = single("homeassistant/vacuum/x/config", """{"stat_t":"s","cmd_t":"c","fanspd_lst":["Silent"],"sup_feat":["start","fan_speed"]}""").entity.capabilities
        assertEquals(emptyList(), noFanTopic.fanSpeeds)
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

    @Test
    fun `json light effects and colour modes`() {
        val e = single("homeassistant/light/0x1051dbfffe1a4058/light/config", fixture("zigled_light.json")).entity
        assertEquals(listOf("solid", "breathe", "comet", "wipe", "sparkle", "rainbow", "candle", "fire_1d", "plasma_2d"), e.capabilities.effects)
        assertEquals(setOf("xy"), e.capabilities.colorModes)
        val noFx = single(
            "homeassistant/light/x/config",
            """{"schema":"json","command_topic":"x/set","unique_id":"x","effect":false,"effect_list":["a"]}""",
        ).entity
        assertEquals(emptyList(), noFx.capabilities.effects)
    }

    @Test
    fun `default light effects need an effect command topic`() {
        val withTopic = single(
            "homeassistant/light/y/config",
            """{"command_topic":"y/set","effect_command_topic":"y/fx","effect_list":["solid","rainbow"],"unique_id":"y"}""",
        ).entity
        assertEquals(listOf("solid", "rainbow"), withTopic.capabilities.effects)
        val without = single("homeassistant/light/z/config", """{"command_topic":"z/set","effect_list":["solid"],"unique_id":"z"}""").entity
        assertEquals(emptyList(), without.capabilities.effects)
    }

    @Test
    fun `alarm code flags and format`() {
        fun caps(json: String) = single("homeassistant/alarm_control_panel/a/config", json).entity.capabilities
        val remote = caps("""{"cmd_t":"c","code":"REMOTE_CODE","code_arm_required":false}""")
        assertEquals(false, remote.codeArmRequired)
        assertEquals(true, remote.codeDisarmRequired)
        assertEquals(CodeFormat.NUMBER, remote.codeFormat)
        assertEquals(CodeFormat.TEXT, caps("""{"cmd_t":"c","code":"REMOTE_CODE_TEXT"}""").codeFormat)
        assertEquals(CodeFormat.NUMBER, caps("""{"cmd_t":"c","code":"0420"}""").codeFormat)
        assertEquals(CodeFormat.TEXT, caps("""{"cmd_t":"c","code":"s3cret"}""").codeFormat)
        val none = caps("""{"cmd_t":"c"}""")
        assertEquals(false, none.codeArmRequired)
        assertEquals(false, none.codeDisarmRequired)
    }

    @Test
    fun `abbreviated water_heater config becomes a WATER_HEATER entity`() {
        val built = single(
            "homeassistant/water_heater/jeedom/water_heater/config",
            """{"uniq_id":"jeedom_water_heater","modes":["off","eco","performance"],
               "min_temp":40,"max_temp":65,"precision":1.0,
               "curr_temp_t":"water-heater/data","curr_temp_tpl":"{{ value_json.temp_0 }}",
               "mode_stat_t":"jeedom/water_heater/state","mode_stat_tpl":"{{ value_json.mode }}",
               "temp_stat_t":"jeedom/water_heater/state","temp_stat_tpl":"{{ value_json.target }}",
               "mode_cmd_t":"jeedom/water_heater/mode/set",
               "temp_cmd_t":"jeedom/water_heater/temperature/set"}""",
        )
        assertEquals(Kind.WATER_HEATER, built.entity.kind)
        val caps = built.entity.capabilities
        assertEquals(listOf("off", "eco", "performance"), caps.hvacModes)
        assertEquals(40.0, caps.minTemp)
        assertEquals(65.0, caps.maxTemp)
        assertEquals(1.0, caps.tempStep)
    }

    @Test
    fun `water_heater defaults when the config omits ranges`() {
        val built = single(
            "homeassistant/water_heater/x/config",
            """{"mode_cmd_t":"m/set"}""",
        )
        val caps = built.entity.capabilities
        assertEquals(43.0, caps.minTemp)
        assertEquals(60.0, caps.maxTemp)
        assertEquals(1.0, caps.tempStep)
        assertEquals(
            listOf("off", "eco", "electric", "gas", "heat_pump", "high_demand", "performance"),
            caps.hvacModes,
        )
    }

    @Test
    fun `water_heater without a command topic is still discovered`() {
        val built = single(
            "homeassistant/water_heater/read/config",
            """{"curr_temp_t":"t"}""",
        )
        assertEquals(Kind.WATER_HEATER, built.entity.kind)
    }
}
