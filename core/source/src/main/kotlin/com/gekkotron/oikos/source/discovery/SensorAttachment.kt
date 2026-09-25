package com.gekkotron.oikos.source.discovery

import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.state.mqtt.MqttBindings

/**
 * Matches a [Kind.SENSOR] to the [Kind.COVER] it duplicates: Z2M publishes a cover and a separate
 * "Moving" sensor off the same state payload. A sensor is folded into a cover when both share a
 * device, read the same state topic, and the sensor's value_template reads exactly one JSON field
 * that the cover's value_template also reads.
 */
object SensorAttachment {
    private val SOLE_FIELD = Regex("""^\{\{\s*value_json(?:\.(\w+)|\[\s*["'](\w+)["']\s*\])\s*\}\}$""")

    /** uniqueId -> owning cover's uniqueId, for every sensor that is part of a cover. */
    fun compute(entities: Collection<Entity>): Map<String, String> {
        val covers = entities.filter { it.kind == Kind.COVER }
        if (covers.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (sensor in entities) {
            if (sensor.kind != Kind.SENSOR) continue
            val deviceId = sensor.deviceId ?: continue
            val sensorBindings = sensor.bindings as? MqttBindings ?: continue
            val stateTopic = sensorBindings.str("state_topic") ?: continue
            val field = soleField(sensorBindings.str("value_template")) ?: continue
            val cover = covers.firstOrNull { cover ->
                cover.deviceId == deviceId &&
                    (cover.bindings as? MqttBindings)?.str("state_topic") == stateTopic &&
                    referencesField(cover.bindings as? MqttBindings, field)
            } ?: continue
            result[sensor.uniqueId] = cover.uniqueId
        }
        return result
    }

    private fun soleField(template: String?): String? {
        val match = template?.trim()?.let(SOLE_FIELD::matchEntire) ?: return null
        return match.groupValues[1].ifEmpty { match.groupValues[2] }
    }

    private fun referencesField(bindings: MqttBindings?, field: String): Boolean {
        val template = bindings?.str("value_template") ?: return false
        val escaped = Regex.escape(field)
        val dot = Regex("""value_json\.$escaped\b""")
        val bracket = Regex("""value_json\[\s*["']$escaped["']\s*\]""")
        return dot.containsMatchIn(template) || bracket.containsMatchIn(template)
    }
}
