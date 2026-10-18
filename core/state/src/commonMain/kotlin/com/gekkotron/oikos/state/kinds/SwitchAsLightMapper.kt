package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings

/** A switch the user shows as a light: speaks switch payloads, reports [StateValue.Light]. Brightness, color and effects are ignored. */
object SwitchAsLightMapper : KindMapper {
    override fun stateTopics(b: MqttBindings): Set<String> = SwitchMapper.stateTopics(b)

    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded =
        when (val d = SwitchMapper.decode(b, topic, payload, previous.asOnOff())) {
            is Decoded.State -> Decoded.State(d.value.asLight())
            else -> d
        }

    override fun encode(b: MqttBindings, command: Command, previous: StateValue): Encoded =
        SwitchMapper.encode(b, command, previous.asOnOff())

    override fun predict(command: Command, previous: StateValue): StateValue? =
        SwitchMapper.predict(command, previous.asOnOff())?.asLight()

    override fun feedbackTopics(b: MqttBindings, command: Command): Set<String> = SwitchMapper.feedbackTopics(b, command)

    override fun settle(command: Command, predicted: StateValue): StateValue = SwitchMapper.settle(command, predicted)

    private fun StateValue.asOnOff(): StateValue = if (this is StateValue.Light) StateValue.OnOff(on) else this

    private fun StateValue.asLight(): StateValue = if (this is StateValue.OnOff) StateValue.Light(on) else this
}
