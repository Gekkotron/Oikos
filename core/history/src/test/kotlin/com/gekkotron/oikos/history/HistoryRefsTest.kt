package com.gekkotron.oikos.history

import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.state.mqtt.MqttBindings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryRefsTest {
    private fun entity(kind: Kind, unit: String?, vararg config: Pair<String, String>) = Entity(
        uniqueId = "e", deviceId = null, name = "E", kind = kind, deviceClass = null, unit = unit, icon = null,
        capabilities = Capabilities(), bindings = MqttBindings("sensor", JsonObject(config.associate { (k, v) -> k to JsonPrimitive(v) })),
    )

    @Test
    fun `dot key`() = assertEquals("temperature", HistoryRefs.jsonKey("{{ value_json.temperature }}"))

    @Test
    fun `bracket key with accents and a dot`() = assertEquals("température_int.", HistoryRefs.jsonKey("{{ value_json['température_int.'] }}"))

    @Test
    fun `double-quoted key and filters`() = assertEquals("power", HistoryRefs.jsonKey("""{{ value_json["power"] | float | round(1) }}"""))

    @Test
    fun `anything else has no key`() {
        listOf(null, "", "{{ value }}", "{{ value_json.a.b }}", "{{ value_json.a + 1 }}", "{% if value_json.a %}1{% endif %}")
            .forEach { assertNull(HistoryRefs.jsonKey(it), it.toString()) }
    }

    @Test
    fun `a numeric sensor gets its state topic and key`() {
        val e = entity(Kind.SENSOR, "°C", "state_topic" to "zigbee2mqtt/rooms/living/thermometer", "value_template" to "{{ value_json.temperature }}")
        assertEquals(HistoryRef("zigbee2mqtt/rooms/living/thermometer", "temperature"), HistoryRefs.of(e))
    }

    @Test
    fun `a sensor without a unit, a raw payload or another kind has none`() {
        assertNull(HistoryRefs.of(entity(Kind.SENSOR, null, "state_topic" to "t", "value_template" to "{{ value_json.state }}")))
        assertNull(HistoryRefs.of(entity(Kind.SENSOR, "W", "state_topic" to "t")))
        assertNull(HistoryRefs.of(entity(Kind.SWITCH, "W", "state_topic" to "t", "value_template" to "{{ value_json.state }}")))
    }

    @Test
    fun `a climate uses its current temperature`() {
        val e = entity(Kind.CLIMATE, null, "current_temperature_topic" to "jeedom/data/rooms/remi/climatisation", "current_temperature_template" to "{{ value_json['température_int.'] }}")
        assertEquals(HistoryRef("jeedom/data/rooms/remi/climatisation", "température_int."), HistoryRefs.of(e))
    }

    @Test
    fun `changeRef reads a binary sensor's topic and key`() {
        val door = entity(Kind.BINARY_SENSOR, null, "state_topic" to "zigbee2mqtt/entrance/door", "value_template" to "{{ value_json.contact }}")
        assertEquals(HistoryRef("zigbee2mqtt/entrance/door", "contact"), HistoryRefs.changeRef(door))
        val bracket = entity(Kind.BINARY_SENSOR, null, "state_topic" to "z/d", "value_template" to "{{ value_json['occupancy'] }}")
        assertEquals(HistoryRef("z/d", "occupancy"), HistoryRefs.changeRef(bracket))
    }

    @Test
    fun `changeRef is null for other kinds, no topic or an unreadable template`() {
        assertNull(HistoryRefs.changeRef(entity(Kind.SENSOR, "°C", "state_topic" to "t", "value_template" to "{{ value_json.temperature }}")))
        assertNull(HistoryRefs.changeRef(entity(Kind.BINARY_SENSOR, null, "value_template" to "{{ value_json.contact }}")))
        assertNull(HistoryRefs.changeRef(entity(Kind.BINARY_SENSOR, null, "state_topic" to "t", "value_template" to "{{ value }}")))
    }

    @Test
    fun `a binary sensor gets a chart ref from its state topic and template`() {
        val door = entity(Kind.BINARY_SENSOR, null, "state_topic" to "zigbee2mqtt/entrance/door", "value_template" to "{{ value_json.contact }}")
        assertEquals(HistoryRef("zigbee2mqtt/entrance/door", "contact"), HistoryRefs.of(door))
    }

    @Test
    fun `a binary sensor without a topic or a readable template has no chart ref`() {
        assertNull(HistoryRefs.of(entity(Kind.BINARY_SENSOR, null, "value_template" to "{{ value_json.contact }}")))
        assertNull(HistoryRefs.of(entity(Kind.BINARY_SENSOR, null, "state_topic" to "t", "value_template" to "{{ value }}")))
    }
}
