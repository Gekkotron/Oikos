package com.gekkotron.oikos.history

import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.state.mqtt.MqttBindings

/** Where Athena-Core recorded an entity: its state topic and the JSON key its template reads (history spec §2, §4.1). */
object HistoryRefs {
    // Literal `]` and `}` are escaped: Android's ICU regex engine rejects them bare, unlike the JVM's.
    private val KEY = Regex(
        """^\{\{\s*value_json(?:\.([A-Za-z_][A-Za-z0-9_]*)|\[\s*(['"])(.+?)\2\s*\])\s*(?:\|[^{}]*)?\}\}$""",
    )

    fun jsonKey(template: String?): String? {
        val m = KEY.matchEntire(template?.trim() ?: return null) ?: return null
        return m.groupValues[1].ifEmpty { m.groupValues[3] }.ifEmpty { null }
    }

    fun of(entity: Entity): HistoryRef? {
        val b = entity.bindings as? MqttBindings ?: return null
        val (topicKey, templateKey) = when (entity.kind) {
            Kind.SENSOR -> if (entity.unit.isNullOrBlank()) return null else "state_topic" to "value_template"
            Kind.CLIMATE -> "current_temperature_topic" to "current_temperature_template"
            else -> return null
        }
        val topic = b.str(topicKey) ?: return null
        val key = jsonKey(b.str(templateKey)) ?: return null
        return HistoryRef(topic, key)
    }
}
