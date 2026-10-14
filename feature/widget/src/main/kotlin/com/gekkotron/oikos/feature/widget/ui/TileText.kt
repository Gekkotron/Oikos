package com.gekkotron.oikos.feature.widget.ui

import android.content.Context
import com.gekkotron.oikos.designsystem.alarm.alarmLabel
import com.gekkotron.oikos.feature.widget.R
import com.gekkotron.oikos.feature.widget.model.TileValue
import com.gekkotron.oikos.model.CoverState
import java.text.NumberFormat
import java.util.Locale

/** [TileValue] → the tile's state line. Same number rules as Home's `CardFormatter`: one decimal, locale separators. */
object TileText {
    fun of(context: Context, value: TileValue, locale: Locale): String = when (value) {
        TileValue.None -> "—"
        is TileValue.OnOff -> onOff(context, value.on)
        is TileValue.Light -> if (value.on && value.percent != null) "${value.percent} %" else onOff(context, value.on)
        is TileValue.Measure -> listOfNotNull(number(value.value, locale), value.unit?.takeIf { it.isNotBlank() }).joinToString(" ")
        is TileValue.Text -> value.value
        is TileValue.Contact -> context.getString(if (value.on) contactOn(value.deviceClass) else contactOff(value.deviceClass))
        is TileValue.Cover -> cover(context, value)
        is TileValue.Climate -> (value.target ?: value.current)?.let { number(it, locale) + "°" } ?: value.mode ?: "—"
        is TileValue.Alarm -> context.getString(alarmLabel(value.state))
    }

    fun number(value: Double, locale: Locale): String = NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = 1
        minimumFractionDigits = 0
    }.format(value)

    private fun onOff(context: Context, on: Boolean) = context.getString(if (on) R.string.widget_on else R.string.widget_off)

    private fun cover(context: Context, value: TileValue.Cover): String {
        val partial = value.position?.takeIf { it in 1..99 }
        return when (value.state) {
            CoverState.OPEN -> partial?.let { context.getString(R.string.widget_open_percent, it) } ?: context.getString(R.string.widget_open)
            CoverState.CLOSED -> context.getString(R.string.widget_closed)
            CoverState.OPENING -> context.getString(R.string.widget_opening)
            CoverState.CLOSING -> context.getString(R.string.widget_closing)
            CoverState.STOPPED -> partial?.let { context.getString(R.string.widget_open_percent, it) } ?: context.getString(R.string.widget_stopped)
            CoverState.UNKNOWN -> "—"
        }
    }

    private fun contactOn(deviceClass: String?) = if (deviceClass in OPENINGS) R.string.widget_open else R.string.widget_detected

    private fun contactOff(deviceClass: String?) = if (deviceClass in OPENINGS) R.string.widget_closed else R.string.widget_clear

    private val OPENINGS = setOf("door", "window", "garage_door", "opening")
}
