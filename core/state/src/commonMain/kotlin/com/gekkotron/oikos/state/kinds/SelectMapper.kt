package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.state.template.asPayloadString

object SelectMapper : KindMapper {
    private fun options(b: MqttBindings) = b.strList("options").orEmpty()

    override fun stateTopics(b: MqttBindings): Set<String> = setOfNotNull(b.str("state_topic"))

    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded =
        renderIncoming(b, "value_template", payload).decodeWith { v ->
            v.asPayloadString()?.takeIf { it in options(b) }?.let { Decoded.State(StateValue.Text(it)) } ?: Decoded.Ignored
        }

    override fun encode(b: MqttBindings, command: Command, previous: StateValue): Encoded {
        if (command !is Command.SelectOption) return Encoded.NotSupported("select cannot $command")
        if (command.option !in options(b)) return Encoded.NotSupported("option '${command.option}' not in options")
        return publish(b, "command_topic", "command_template", command.option)
    }

    override fun predict(command: Command, previous: StateValue): StateValue? =
        (command as? Command.SelectOption)?.let { StateValue.Text(it.option) }
}
