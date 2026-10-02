package com.gekkotron.oikos.feature.home.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gekkotron.oikos.designsystem.alarm.alarmLabel
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.feature.home.presentation.CardValue
import com.gekkotron.oikos.model.CoverState

private val OPENING_CLASSES = setOf("door", "window", "garage_door", "opening")
private val MOTION_CLASSES = setOf("motion", "occupancy", "presence")

@Composable
internal fun cardValueText(value: CardValue): String = when (value) {
    CardValue.None -> stringResource(R.string.value_none)
    is CardValue.OnOff -> stringResource(if (value.on) R.string.value_on else R.string.value_off)
    is CardValue.Brightness -> stringResource(R.string.value_percent, value.percent)
    is CardValue.Measurement -> listOfNotNull(value.text, value.unit).joinToString(" ")
    is CardValue.Text -> value.value
    is CardValue.Cover -> coverText(value)
    is CardValue.Climate ->
        listOfNotNull(value.current?.let { "$it°" }, value.mode?.let { modeLabel(it) })
            .joinToString(" · ")
            .ifEmpty { stringResource(R.string.value_none) }
    is CardValue.Alarm -> stringResource(alarmLabel(value.state))
    is CardValue.Contact -> stringResource(
        when (value.deviceClass) {
            in OPENING_CLASSES -> if (value.on) R.string.value_contact_open else R.string.value_contact_closed
            in MOTION_CLASSES -> if (value.on) R.string.value_motion_detected else R.string.value_motion_clear
            else -> if (value.on) R.string.value_on else R.string.value_off
        },
    )
}

@Composable
private fun coverText(value: CardValue.Cover): String {
    val position = value.position
    return when {
        value.state == CoverState.OPENING -> stringResource(R.string.value_cover_opening)
        value.state == CoverState.CLOSING -> stringResource(R.string.value_cover_closing)
        value.state == CoverState.CLOSED || position == 0 -> stringResource(R.string.value_cover_closed)
        position != null && position in 1..99 -> stringResource(R.string.value_cover_position, position)
        value.state == CoverState.OPEN || position == 100 -> stringResource(R.string.value_cover_open)
        value.state == CoverState.STOPPED -> stringResource(R.string.value_cover_stopped)
        else -> stringResource(R.string.value_none)
    }
}

@Composable
internal fun modeLabel(mode: String): String = when (mode) {
    "off" -> stringResource(R.string.mode_off)
    "cool" -> stringResource(R.string.mode_cool)
    "heat" -> stringResource(R.string.mode_heat)
    "auto" -> stringResource(R.string.mode_auto)
    "dry" -> stringResource(R.string.mode_dry)
    "fan_only" -> stringResource(R.string.mode_fan_only)
    else -> mode.replaceFirstChar { it.uppercase() }
}
