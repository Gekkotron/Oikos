package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings
import kotlin.math.roundToInt
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** HA vacuum, state schema: JSON state on `state_topic`, plain payloads on `command_topic`. */
object VacuumMapper : KindMapper {
    val STATES = setOf("cleaning", "docked", "paused", "idle", "returning", "error")

    /** HA's default `supported_features` when the config has none. */
    val DEFAULT_FEATURES = listOf("start", "stop", "return_home", "status", "battery", "clean_spot")

    /** Feature name → Oikos action, in the order the sheet shows them. */
    private val ACTION_FEATURES = linkedMapOf(
        "start" to "start", "pause" to "pause", "stop" to "stop", "return_home" to "return_to_base", "locate" to "locate",
    )

    /** Optimistic state shown after each action, until the state topic confirms. */
    private val PREDICTED = mapOf("start" to "cleaning", "pause" to "paused", "stop" to "idle", "return_to_base" to "returning")

    private fun features(b: MqttBindings): List<String> = b.strList("supported_features") ?: DEFAULT_FEATURES

    fun actions(b: MqttBindings): List<String> = features(b).let { f -> ACTION_FEATURES.filterKeys { it in f }.values.toList() }

    fun fanSpeeds(b: MqttBindings): List<String> =
        if ("fan_speed" in features(b) && b.str("set_fan_speed_topic") != null) b.strList("fan_speed_list").orEmpty() else emptyList()

    override fun stateTopics(b: MqttBindings): Set<String> = setOfNotNull(b.str("state_topic"))

    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded {
        if (topic != b.str("state_topic")) return Decoded.Ignored
        val json = try {
            Json.parseToJsonElement(payload) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return Decoded.Error("vacuum state is not a JSON object")
        val prev = previous as? StateValue.Vacuum ?: StateValue.Vacuum(null, null, null)
        val state = (json["state"] as? JsonPrimitive)?.contentOrNull?.takeIf { it in STATES } ?: prev.state
        val battery = (json["battery_level"] as? JsonPrimitive)?.doubleOrNull?.roundToInt()?.coerceIn(0, 100) ?: prev.battery
        // An absent key keeps the previous speed; an explicit null means "no known preset".
        val fanSpeed = if ("fan_speed" in json) (json["fan_speed"] as? JsonPrimitive)?.contentOrNull else prev.fanSpeed
        return Decoded.State(StateValue.Vacuum(state, battery, fanSpeed))
    }

    override fun encode(b: MqttBindings, command: Command, previous: StateValue): Encoded = when (command) {
        is Command.VacuumAction -> action(b, command.action)
        is Command.SetFanSpeed ->
            if (command.speed !in fanSpeeds(b)) Encoded.NotSupported("fan speed '${command.speed}' not supported")
            else publish(b, "set_fan_speed_topic", null, command.speed)
        Command.TurnOff -> action(b, "return_to_base")
        else -> Encoded.NotSupported("vacuum cannot $command")
    }

    private fun action(b: MqttBindings, action: String): Encoded {
        if (action !in actions(b)) return Encoded.NotSupported("vacuum action '$action' not supported")
        val topic = b.str("command_topic") ?: return Encoded.NotSupported("no command_topic configured")
        return Encoded.Publish(listOf(outgoing(b, topic, b.str("payload_$action", action))))
    }

    /**
     * Optimistic: a bridge such as Roborock2Mqtt runs the command and then a status query before it
     * publishes, which can outlast the confirmation window; the next state message corrects a wrong guess.
     */
    override fun feedbackTopics(b: MqttBindings, command: Command): Set<String> = emptySet()

    override fun predict(command: Command, previous: StateValue): StateValue? {
        val prev = previous as? StateValue.Vacuum ?: StateValue.Vacuum(null, null, null)
        return when (command) {
            is Command.VacuumAction -> PREDICTED[command.action]?.let { prev.copy(state = it) }
            is Command.SetFanSpeed -> prev.copy(fanSpeed = command.speed)
            Command.TurnOff -> prev.copy(state = "returning")
            else -> null
        }
    }
}
