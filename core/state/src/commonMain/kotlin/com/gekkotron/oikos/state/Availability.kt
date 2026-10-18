package com.gekkotron.oikos.state

import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.state.template.TemplateResult
import com.gekkotron.oikos.state.template.Templates
import com.gekkotron.oikos.state.template.asPayloadString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal data class AvailabilityRule(
    val topic: String,
    val template: String?,
    val payloadAvailable: String,
    val payloadNotAvailable: String,
)

internal enum class AvailabilityMode { LATEST, ALL, ANY }

internal data class AvailabilitySpec(val rules: List<AvailabilityRule>, val mode: AvailabilityMode) {
    val topics: Set<String> = rules.mapTo(linkedSetOf()) { it.topic }

    companion object {
        fun of(b: MqttBindings): AvailabilitySpec? {
            val rules = mutableListOf<AvailabilityRule>()
            (b.config["availability"] as? JsonArray)?.forEach { item ->
                val ib = MqttBindings(b.component, item as? JsonObject ?: return@forEach)
                val topic = ib.str("topic") ?: return@forEach
                rules += AvailabilityRule(
                    topic, ib.str("value_template"),
                    ib.str("payload_available", "online"), ib.str("payload_not_available", "offline"),
                )
            }
            b.str("availability_topic")?.let {
                rules += AvailabilityRule(
                    it, b.str("availability_template"),
                    b.str("payload_available", "online"), b.str("payload_not_available", "offline"),
                )
            }
            if (rules.isEmpty()) return null
            val mode = when (b.str("availability_mode")) {
                "all" -> AvailabilityMode.ALL
                "any" -> AvailabilityMode.ANY
                else -> AvailabilityMode.LATEST
            }
            return AvailabilitySpec(rules, mode)
        }
    }
}

internal class AvailabilityTracker(val spec: AvailabilitySpec) {
    private val byTopic = mutableMapOf<String, Boolean>()
    private var latest: Boolean? = null

    /** Returns true when a rule recognised the payload. */
    fun onMessage(topic: String, payload: String): Boolean {
        var recognised = false
        for (rule in spec.rules) {
            if (rule.topic != topic) continue
            // If the template cannot evaluate this payload (e.g. plain "online"), fall back to the raw payload.
            val rendered = rule.template
                ?.let { (Templates.renderValue(it, payload) as? TemplateResult.Value)?.value.asPayloadString() }
                ?: payload
            val available = when (rendered) {
                rule.payloadAvailable -> true
                rule.payloadNotAvailable -> false
                else -> continue
            }
            byTopic[topic] = available
            latest = available
            recognised = true
        }
        return recognised
    }

    val available: Boolean
        get() = when (spec.mode) {
            AvailabilityMode.LATEST -> latest ?: false
            AvailabilityMode.ALL -> spec.topics.all { byTopic[it] == true }
            AvailabilityMode.ANY -> spec.topics.any { byTopic[it] == true }
        }
}
