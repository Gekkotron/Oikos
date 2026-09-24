package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.state.template.asPayloadString

object SwitchMapper : KindMapper {
    override fun stateTopics(b: MqttBindings): Set<String> = setOfNotNull(b.str("state_topic"))

    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded =
        renderIncoming(b, "value_template", payload).decodeWith { v ->
            val s = v.asPayloadString() ?: return@decodeWith Decoded.Ignored
            onOffFrom(
                s,
                on = b.str("state_on") ?: b.str("payload_on", "ON"),
                off = b.str("state_off") ?: b.str("payload_off", "OFF"),
            )
        }

    override fun encode(b: MqttBindings, command: Command, previous: StateValue): Encoded {
        val on = targetOn(command, previous) ?: return Encoded.NotSupported("switch cannot $command")
        return publish(b, "command_topic", "command_template", if (on) b.str("payload_on", "ON") else b.str("payload_off", "OFF"))
    }

    override fun predict(command: Command, previous: StateValue): StateValue? =
        targetOn(command, previous)?.let { StateValue.OnOff(it) }
}
