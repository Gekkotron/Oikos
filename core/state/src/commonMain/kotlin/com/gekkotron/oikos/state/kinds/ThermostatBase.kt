package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.state.template.asDouble
import com.gekkotron.oikos.state.template.asPayloadString
import com.gekkotron.oikos.state.template.formatNumber

/**
 * The HA climate topic/template plumbing shared by [ClimateMapper] and [WaterHeaterMapper]:
 * current / target / mode on the standard `*_temperature_*` and `mode_*` keys, with the standard
 * `_template` renderers. Fan-mode handling stays in [ClimateMapper]; this helper never touches it.
 */
internal object ThermostatBase {
    fun stateTopics(b: MqttBindings): Set<String> = setOfNotNull(
        b.str("current_temperature_topic"),
        b.str("temperature_state_topic"),
        b.str("mode_state_topic"),
    )

    /** Decodes the three climate topics onto [previous]'s [StateValue.Climate]; keeps `fanMode` as it was. Null [modes] accepts any non-blank mode. */
    fun decode(
        b: MqttBindings,
        topic: String,
        payload: String,
        previous: StateValue,
        modes: List<String>?,
    ): Decoded {
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
                val mode = v.asPayloadString()?.takeIf { if (modes == null) it.isNotBlank() else it in modes } ?: return@part false
                climate = climate.copy(mode = mode)
                true
            }
        }
        return parts.result(climate)
    }

    fun encodeTemperature(b: MqttBindings, temperature: Double, min: Double, max: Double): Encoded =
        if (temperature !in min..max) Encoded.NotSupported("temperature $temperature outside $min..$max")
        else publish(b, "temperature_command_topic", "temperature_command_template", formatNumber(temperature))

    fun encodeMode(b: MqttBindings, mode: String, modes: List<String>): Encoded =
        if (mode !in modes) Encoded.NotSupported("mode '$mode' not supported")
        else publish(b, "mode_command_topic", "mode_command_template", mode)

    fun feedbackTopics(b: MqttBindings, command: Command): Set<String> = when (command) {
        is Command.SetTemperature -> setOfNotNull(b.str("temperature_state_topic"))
        is Command.SetHvacMode, Command.TurnOff -> setOfNotNull(b.str("mode_state_topic"))
        else -> stateTopics(b)
    }

    fun predict(command: Command, previous: StateValue): StateValue? {
        val prev = previous as? StateValue.Climate ?: StateValue.Climate(null, null, null)
        return when (command) {
            is Command.SetTemperature -> prev.copy(target = command.temperature)
            is Command.SetHvacMode -> prev.copy(mode = command.mode)
            Command.TurnOff -> prev.copy(mode = "off")
            else -> null
        }
    }
}
