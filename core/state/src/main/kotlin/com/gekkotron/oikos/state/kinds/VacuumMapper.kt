package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings

/** HA vacuum, state schema: JSON state on `state_topic`, plain payloads on `command_topic`. */
object VacuumMapper : KindMapper {
    val STATES = setOf("cleaning", "docked", "paused", "idle", "returning", "error")

    /** HA's default `supported_features` when the config has none. */
    val DEFAULT_FEATURES = listOf("start", "stop", "return_home", "status", "battery", "clean_spot")

    /** Feature name → Oikos action, in the order the sheet shows them. */
    private val ACTION_FEATURES = linkedMapOf(
        "start" to "start", "pause" to "pause", "stop" to "stop", "return_home" to "return_to_base", "locate" to "locate",
    )

    private fun features(b: MqttBindings): List<String> = b.strList("supported_features") ?: DEFAULT_FEATURES

    fun actions(b: MqttBindings): List<String> = features(b).let { f -> ACTION_FEATURES.filterKeys { it in f }.values.toList() }

    fun fanSpeeds(b: MqttBindings): List<String> =
        if ("fan_speed" in features(b) && b.str("set_fan_speed_topic") != null) b.strList("fan_speed_list").orEmpty() else emptyList()

    override fun stateTopics(b: MqttBindings): Set<String> = setOfNotNull(b.str("state_topic"))

    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded = Decoded.Ignored
}
