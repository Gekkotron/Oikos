package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings

/**
 * HA `water_heater`. Reuses [ThermostatBase] for current / target / mode and keeps the state as a
 * [StateValue.Climate] with `fanMode = null`. The UI never sends `off` (the Force toggle flips
 * between `eco`/`performance` and the sheet filters `off` out of the chips); the mapper itself
 * still accepts `SetHvacMode("off")` or `TurnOff` when `off` is in the modes. An explicit empty `modes`
 * (the WaterHeaterController, which only reports `heat`/`off`) keeps any reported mode but accepts no
 * mode command.
 */
object WaterHeaterMapper : KindMapper {
    val DEFAULT_MODES = listOf("off", "eco", "electric", "gas", "heat_pump", "high_demand", "performance")
    const val DEFAULT_MIN_TEMP = 43.0
    const val DEFAULT_MAX_TEMP = 60.0
    const val DEFAULT_TEMP_STEP = 1.0

    fun modes(b: MqttBindings): List<String> = b.strList("modes") ?: DEFAULT_MODES

    override fun stateTopics(b: MqttBindings): Set<String> = ThermostatBase.stateTopics(b)

    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded =
        ThermostatBase.decode(b, topic, payload, previous, modes(b).ifEmpty { null })

    override fun encode(b: MqttBindings, command: Command, previous: StateValue): Encoded = when (command) {
        is Command.SetTemperature -> ThermostatBase.encodeTemperature(
            b, command.temperature, b.double("min_temp") ?: DEFAULT_MIN_TEMP, b.double("max_temp") ?: DEFAULT_MAX_TEMP,
        )
        is Command.SetHvacMode -> ThermostatBase.encodeMode(b, command.mode, modes(b))
        Command.TurnOff -> ThermostatBase.encodeMode(b, "off", modes(b))
        else -> Encoded.NotSupported("water heater cannot $command")
    }

    override fun feedbackTopics(b: MqttBindings, command: Command): Set<String> =
        ThermostatBase.feedbackTopics(b, command)

    override fun predict(command: Command, previous: StateValue): StateValue? =
        ThermostatBase.predict(command, previous)
}
