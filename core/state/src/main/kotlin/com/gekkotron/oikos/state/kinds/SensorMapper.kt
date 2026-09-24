package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.state.template.asPayloadString

object SensorMapper : KindMapper {
    private val UNKNOWN_WORDS = setOf("unknown", "unavailable", "None")

    override fun stateTopics(b: MqttBindings): Set<String> = setOfNotNull(b.str("state_topic"))

    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded =
        renderIncoming(b, "value_template", payload).decodeWith { v ->
            val value = when (v) {
                null -> StateValue.Unknown
                is Number -> StateValue.Numeric(v.toDouble())
                else -> {
                    val s = v.asPayloadString().orEmpty().trim()
                    when {
                        s.isEmpty() || s in UNKNOWN_WORDS -> StateValue.Unknown
                        else -> s.toDoubleOrNull()?.let { StateValue.Numeric(it) } ?: StateValue.Text(s)
                    }
                }
            }
            Decoded.State(value)
        }
}
