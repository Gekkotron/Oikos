package com.gekkotron.oikos.source.discovery

import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.state.mqtt.MqttBindings
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SensorAttachmentTest {
    private fun cover(id: String, deviceId: String, stateTopic: String, valueTemplate: String) = Entity(
        uniqueId = id,
        deviceId = deviceId,
        name = id,
        kind = Kind.COVER,
        deviceClass = null,
        unit = null,
        icon = null,
        capabilities = Capabilities(),
        bindings = MqttBindings("cover", buildJsonObject { put("state_topic", stateTopic); put("value_template", valueTemplate) }),
    )

    private fun sensor(id: String, deviceId: String?, stateTopic: String, valueTemplate: String) = Entity(
        uniqueId = id,
        deviceId = deviceId,
        name = id,
        kind = Kind.SENSOR,
        deviceClass = null,
        unit = null,
        icon = null,
        capabilities = Capabilities(),
        bindings = MqttBindings("sensor", buildJsonObject { put("state_topic", stateTopic); put("value_template", valueTemplate) }),
    )

    @Test
    fun `a sensor reading the same field on the same topic is attached to the cover`() {
        val c = cover("cover1", "dev", "z/shutter", """{{ value_json["moving"] }}""")
        val s = sensor("moving1", "dev", "z/shutter", """{{ value_json["moving"] }}""")
        assertEquals(mapOf("moving1" to "cover1"), SensorAttachment.compute(listOf(c, s)))
        assertEquals(mapOf("moving1" to "cover1"), SensorAttachment.compute(listOf(s, c)))
    }

    @Test
    fun `dot notation and whitespace in the sensor template are tolerated`() {
        val c = cover("cover1", "dev", "z/shutter", """{% if value_json.moving %}{{ value_json.moving }}{% endif %}""")
        val s = sensor("moving1", "dev", "z/shutter", "  {{  value_json.moving  }}  ")
        assertEquals(mapOf("moving1" to "cover1"), SensorAttachment.compute(listOf(c, s)))
    }

    @Test
    fun `a sensor on a different state topic stays unattached`() {
        val c = cover("cover1", "dev", "z/shutter", """{{ value_json["moving"] }}""")
        val s = sensor("moving1", "dev", "z/other", """{{ value_json["moving"] }}""")
        assertTrue(SensorAttachment.compute(listOf(c, s)).isEmpty())
    }

    @Test
    fun `a sensor reading a field the cover does not use stays unattached`() {
        val c = cover("cover1", "dev", "z/shutter", """{{ value_json["position"] }}""")
        val s = sensor("moving1", "dev", "z/shutter", """{{ value_json["moving"] }}""")
        assertTrue(SensorAttachment.compute(listOf(c, s)).isEmpty())
    }

    @Test
    fun `a sensor without a device id stays unattached`() {
        val c = cover("cover1", "dev", "z/shutter", """{{ value_json["moving"] }}""")
        val s = sensor("moving1", null, "z/shutter", """{{ value_json["moving"] }}""")
        assertTrue(SensorAttachment.compute(listOf(c, s)).isEmpty())
    }

    @Test
    fun `a sensor whose template reads more than one field stays unattached`() {
        val c = cover("cover1", "dev", "z/shutter", """{{ value_json["moving"] }}""")
        val s = sensor("moving1", "dev", "z/shutter", """{{ value_json["moving"] }}{{ value_json["state"] }}""")
        assertTrue(SensorAttachment.compute(listOf(c, s)).isEmpty())
    }

    @Test
    fun `no cover means nothing attaches`() {
        val s = sensor("moving1", "dev", "z/shutter", """{{ value_json["moving"] }}""")
        assertTrue(SensorAttachment.compute(listOf(s)).isEmpty())
    }
}
