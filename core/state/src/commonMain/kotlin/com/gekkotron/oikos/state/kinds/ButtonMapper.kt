package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings

object ButtonMapper : KindMapper {
    override fun stateTopics(b: MqttBindings): Set<String> = emptySet()

    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded = Decoded.Ignored

    override fun encode(b: MqttBindings, command: Command, previous: StateValue): Encoded =
        if (command == Command.Press) publish(b, "command_topic", "command_template", b.str("payload_press", "PRESS"))
        else Encoded.NotSupported("button can only be pressed")
}
