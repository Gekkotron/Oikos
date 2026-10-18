package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.state.template.asPayloadString

object BinarySensorMapper : KindMapper {
    override fun stateTopics(b: MqttBindings): Set<String> = setOfNotNull(b.str("state_topic"))

    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded =
        renderIncoming(b, "value_template", payload).decodeWith { v ->
            val s = v.asPayloadString() ?: return@decodeWith Decoded.Ignored
            onOffFrom(s, on = b.str("payload_on", "ON"), off = b.str("payload_off", "OFF"))
        }
}
