package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.state.template.asPayloadString

object AlarmPanelMapper : KindMapper {
    private val STATES = mapOf(
        "disarmed" to AlarmState.DISARMED,
        "armed_home" to AlarmState.ARMED_HOME,
        "armed_away" to AlarmState.ARMED_AWAY,
        "armed_night" to AlarmState.ARMED_NIGHT,
        "armed_vacation" to AlarmState.ARMED_VACATION,
        "armed_custom_bypass" to AlarmState.ARMED_CUSTOM_BYPASS,
        "pending" to AlarmState.PENDING,
        "arming" to AlarmState.ARMING,
        "disarming" to AlarmState.DISARMING,
        "triggered" to AlarmState.TRIGGERED,
    )
    private val REMOTE_CODES = setOf("REMOTE_CODE", "REMOTE_CODE_TEXT")

    fun supportedModes(b: MqttBindings): Set<AlarmMode> =
        b.strList("supported_features")
            ?.mapNotNull { feature -> AlarmMode.entries.firstOrNull { "arm_" + it.name.lowercase() == feature } }
            ?.toSet()
            ?: AlarmMode.entries.toSet()

    fun codeRequired(b: MqttBindings, arming: Boolean): Boolean =
        b.str("code") != null && b.bool(if (arming) "code_arm_required" else "code_disarm_required", true)

    override fun stateTopics(b: MqttBindings): Set<String> = setOfNotNull(b.str("state_topic"))

    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded =
        renderIncoming(b, "value_template", payload).decodeWith { v ->
            STATES[v.asPayloadString()]?.let { Decoded.State(StateValue.Alarm(it)) } ?: Decoded.Ignored
        }

    override fun encode(b: MqttBindings, command: Command, previous: StateValue): Encoded {
        val action: String
        val code: String?
        val arming: Boolean
        when (command) {
            is Command.Arm -> {
                if (command.mode !in supportedModes(b)) return Encoded.NotSupported("arm mode ${command.mode} not supported")
                val suffix = command.mode.name.lowercase()
                action = b.str("payload_arm_$suffix", "ARM_${command.mode.name}")
                code = command.code
                arming = true
            }
            is Command.Disarm -> {
                action = b.str("payload_disarm", "DISARM")
                code = command.code
                arming = false
            }
            else -> return Encoded.NotSupported("alarm panel cannot $command")
        }
        codeProblem(b, code, arming)?.let { return Encoded.NotSupported(it) }
        return publish(b, "command_topic", "command_template", action, mapOf("action" to action, "code" to code))
    }

    private fun codeProblem(b: MqttBindings, given: String?, arming: Boolean): String? {
        val configured = b.str("code") ?: return null
        if (!codeRequired(b, arming)) return null
        if (given.isNullOrEmpty()) return "code required"
        if (configured !in REMOTE_CODES && given != configured) return "wrong code"
        return null
    }

    override fun predict(command: Command, previous: StateValue): StateValue? = when (command) {
        is Command.Arm -> StateValue.Alarm(AlarmState.ARMING)
        is Command.Disarm -> StateValue.Alarm(AlarmState.DISARMING)
        else -> null
    }
}
