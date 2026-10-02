package com.gekkotron.oikos.designsystem.alarm

import androidx.annotation.StringRes
import com.gekkotron.oikos.designsystem.R
import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.AlarmState

/** The short state, for cards ("Armed · Away"). */
@StringRes
fun alarmLabel(state: AlarmState): Int = when (state) {
    AlarmState.DISARMED -> R.string.alarm_disarmed
    AlarmState.ARMED_HOME -> R.string.alarm_armed_home
    AlarmState.ARMED_AWAY -> R.string.alarm_armed_away
    AlarmState.ARMED_NIGHT -> R.string.alarm_armed_night
    AlarmState.ARMED_VACATION -> R.string.alarm_armed_vacation
    AlarmState.ARMED_CUSTOM_BYPASS -> R.string.alarm_armed_custom
    AlarmState.PENDING -> R.string.alarm_pending
    AlarmState.ARMING -> R.string.alarm_arming
    AlarmState.DISARMING -> R.string.alarm_disarming
    AlarmState.TRIGGERED -> R.string.alarm_triggered
}

/** The hero's state line: the delays get their meaning spelled out (security spec §4.2). */
@StringRes
fun alarmHeroLabel(state: AlarmState): Int = when (state) {
    AlarmState.ARMING -> R.string.alarm_exit_delay
    AlarmState.PENDING -> R.string.alarm_entry_delay
    else -> alarmLabel(state)
}

@StringRes
fun armLabel(mode: AlarmMode): Int = when (mode) {
    AlarmMode.HOME -> R.string.alarm_arm_home
    AlarmMode.AWAY -> R.string.alarm_arm_away
    AlarmMode.NIGHT -> R.string.alarm_arm_night
    AlarmMode.VACATION -> R.string.alarm_arm_vacation
    AlarmMode.CUSTOM_BYPASS -> R.string.alarm_arm_custom
}
