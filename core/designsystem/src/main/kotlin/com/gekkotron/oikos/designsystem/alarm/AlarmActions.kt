package com.gekkotron.oikos.designsystem.alarm

import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Command

/** Which alarm buttons to draw for a state (security spec §4.2). [current] is highlighted, not re-sent. */
data class AlarmActions(val arm: List<AlarmMode>, val current: AlarmMode?, val disarm: Boolean, val enabled: Boolean) {
    companion object {
        fun of(state: AlarmState?, available: Boolean, caps: Capabilities): AlarmActions {
            val modes = AlarmMode.entries.filter { it in caps.alarmModes }
            val enabled = available && state != null
            return when (state) {
                null -> AlarmActions(modes, null, disarm = true, enabled = false)
                AlarmState.DISARMED -> AlarmActions(modes, null, disarm = false, enabled = enabled)
                AlarmState.ARMED_HOME, AlarmState.ARMED_AWAY, AlarmState.ARMED_NIGHT,
                AlarmState.ARMED_VACATION, AlarmState.ARMED_CUSTOM_BYPASS ->
                    AlarmActions(modes, armedMode(state), disarm = true, enabled = enabled)
                AlarmState.ARMING, AlarmState.PENDING, AlarmState.TRIGGERED -> AlarmActions(emptyList(), null, disarm = true, enabled = enabled)
                AlarmState.DISARMING -> AlarmActions(emptyList(), null, disarm = false, enabled = enabled)
            }
        }

        fun needsCode(caps: Capabilities, command: Command): Boolean = when (command) {
            is Command.Arm -> caps.codeArmRequired
            is Command.Disarm -> caps.codeDisarmRequired
            else -> false
        }

        private fun armedMode(state: AlarmState): AlarmMode? = when (state) {
            AlarmState.ARMED_HOME -> AlarmMode.HOME
            AlarmState.ARMED_AWAY -> AlarmMode.AWAY
            AlarmState.ARMED_NIGHT -> AlarmMode.NIGHT
            AlarmState.ARMED_VACATION -> AlarmMode.VACATION
            AlarmState.ARMED_CUSTOM_BYPASS -> AlarmMode.CUSTOM_BYPASS
            else -> null
        }
    }
}
