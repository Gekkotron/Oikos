package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.state.template.asDouble
import com.gekkotron.oikos.state.template.asPayloadString
import com.gekkotron.oikos.state.template.formatNumber

object ClimateMapper : KindMapper {
    val DEFAULT_MODES = listOf("auto", "off", "cool", "heat", "dry", "fan_only")
    const val DEFAULT_MIN_TEMP = 7.0
    const val DEFAULT_MAX_TEMP = 35.0
    const val DEFAULT_TEMP_STEP = 1.0

    fun modes(b: MqttBindings): List<String> = b.strList("modes") ?: DEFAULT_MODES

    override fun stateTopics(b: MqttBindings): Set<String> = setOfNotNull(
        b.str("current_temperature_topic"),
        b.str("temperature_state_topic"),
        b.str("mode_state_topic"),
    )

    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded {
        var climate = previous as? StateValue.Climate ?: StateValue.Climate(null, null, null)
        val parts = PartialDecode()
        if (topic == b.str("current_temperature_topic")) {
            parts.part(renderIncoming(b, "current_temperature_template", payload)) { v ->
                val t = v.asDouble() ?: return@part false
                climate = climate.copy(current = t)
                true
            }
        }
        if (topic == b.str("temperature_state_topic")) {
            parts.part(renderIncoming(b, "temperature_state_template", payload)) { v ->
                val t = v.asDouble() ?: return@part false
                climate = climate.copy(target = t)
                true
            }
        }
        if (topic == b.str("mode_state_topic")) {
            parts.part(renderIncoming(b, "mode_state_template", payload)) { v ->
                val mode = v.asPayloadString()?.takeIf { it in modes(b) } ?: return@part false
                climate = climate.copy(mode = mode)
                true
            }
        }
        return parts.result(climate)
    }

    override fun encode(b: MqttBindings, command: Command, previous: StateValue): Encoded = when (command) {
        is Command.SetTemperature -> {
            val min = b.double("min_temp") ?: DEFAULT_MIN_TEMP
            val max = b.double("max_temp") ?: DEFAULT_MAX_TEMP
            if (command.temperature !in min..max) {
                Encoded.NotSupported("temperature ${command.temperature} outside $min..$max")
            } else {
                publish(b, "temperature_command_topic", "temperature_command_template", formatNumber(command.temperature))
            }
        }
        is Command.SetHvacMode ->
            if (command.mode !in modes(b)) Encoded.NotSupported("mode '${command.mode}' not supported")
            else publish(b, "mode_command_topic", "mode_command_template", command.mode)
        Command.TurnOff -> encode(b, Command.SetHvacMode("off"), previous)
        else -> Encoded.NotSupported("climate cannot $command")
    }

    override fun feedbackTopics(b: MqttBindings, command: Command): Set<String> = when (command) {
        is Command.SetTemperature -> setOfNotNull(b.str("temperature_state_topic"))
        is Command.SetHvacMode, Command.TurnOff -> setOfNotNull(b.str("mode_state_topic"))
        else -> stateTopics(b)
    }

    override fun predict(command: Command, previous: StateValue): StateValue? {
        val prev = previous as? StateValue.Climate ?: StateValue.Climate(null, null, null)
        return when (command) {
            is Command.SetTemperature -> prev.copy(target = command.temperature)
            is Command.SetHvacMode -> prev.copy(mode = command.mode)
            Command.TurnOff -> prev.copy(mode = "off")
            else -> null
        }
    }
}
