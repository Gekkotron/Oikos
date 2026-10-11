package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.state.template.asPayloadString

object ClimateMapper : KindMapper {
    val DEFAULT_MODES = listOf("auto", "off", "cool", "heat", "dry", "fan_only")
    val DEFAULT_FAN_MODES = listOf("auto", "low", "medium", "high")
    const val DEFAULT_MIN_TEMP = 7.0
    const val DEFAULT_MAX_TEMP = 35.0
    const val DEFAULT_TEMP_STEP = 1.0

    fun modes(b: MqttBindings): List<String> = b.strList("modes") ?: DEFAULT_MODES
    fun fanModes(b: MqttBindings): List<String> = b.strList("fan_modes") ?: DEFAULT_FAN_MODES

    override fun stateTopics(b: MqttBindings): Set<String> =
        ThermostatBase.stateTopics(b) + setOfNotNull(b.str("fan_mode_state_topic"))

    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded {
        val base = ThermostatBase.decode(b, topic, payload, previous, modes(b))
        if (topic != b.str("fan_mode_state_topic")) return base
        // The fan mode can share its topic with current/target/mode (one JSON doc): apply it on top of that result.
        var climate = (base as? Decoded.State)?.value as? StateValue.Climate
            ?: previous as? StateValue.Climate ?: StateValue.Climate(null, null, null)
        val parts = PartialDecode()
        parts.part(renderIncoming(b, "fan_mode_state_template", payload)) { v ->
            val fan = v.asPayloadString()?.takeIf { it in fanModes(b) } ?: return@part false
            climate = climate.copy(fanMode = fan)
            true
        }
        val fan = parts.result(climate)
        return if (fan is Decoded.State || base is Decoded.Ignored) fan else base
    }

    override fun encode(b: MqttBindings, command: Command, previous: StateValue): Encoded = when (command) {
        is Command.SetTemperature -> ThermostatBase.encodeTemperature(
            b, command.temperature, b.double("min_temp") ?: DEFAULT_MIN_TEMP, b.double("max_temp") ?: DEFAULT_MAX_TEMP,
        )
        is Command.SetHvacMode -> ThermostatBase.encodeMode(b, command.mode, modes(b))
        is Command.SetFanMode ->
            if (command.mode !in fanModes(b)) Encoded.NotSupported("fan mode '${command.mode}' not supported")
            else publish(b, "fan_mode_command_topic", "fan_mode_command_template", command.mode)
        Command.TurnOff -> ThermostatBase.encodeMode(b, "off", modes(b))
        else -> Encoded.NotSupported("climate cannot $command")
    }

    override fun feedbackTopics(b: MqttBindings, command: Command): Set<String> = when (command) {
        is Command.SetFanMode -> setOfNotNull(b.str("fan_mode_state_topic"))
        else -> ThermostatBase.feedbackTopics(b, command)
    }

    override fun predict(command: Command, previous: StateValue): StateValue? {
        val prev = previous as? StateValue.Climate ?: StateValue.Climate(null, null, null)
        return when (command) {
            is Command.SetFanMode -> prev.copy(fanMode = command.mode)
            else -> ThermostatBase.predict(command, previous)
        }
    }
}
