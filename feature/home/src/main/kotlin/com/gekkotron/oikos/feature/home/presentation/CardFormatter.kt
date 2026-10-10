package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

object CardFormatter {
    fun format(entity: Entity, value: StateValue, locale: Locale): CardValue = when (value) {
        StateValue.Unknown, StateValue.Unsupported -> CardValue.None
        is StateValue.OnOff ->
            if (entity.kind == Kind.BINARY_SENSOR) CardValue.Contact(entity.deviceClass, value.on) else CardValue.OnOff(value.on)
        is StateValue.Light -> {
            val brightness = value.brightness
            if (value.on && brightness != null) CardValue.Brightness(percent(brightness)) else CardValue.OnOff(value.on)
        }
        is StateValue.Numeric -> CardValue.Measurement(number(value.value, locale), entity.unit)
        is StateValue.Text -> CardValue.Text(value.value)
        is StateValue.Cover -> CardValue.Cover(value.state, value.position)
        is StateValue.Climate ->
            CardValue.Climate(value.current?.let { number(it, locale) }, value.target?.let { number(it, locale) }, value.mode)
        is StateValue.Alarm -> CardValue.Alarm(value.state)
        is StateValue.Vacuum -> CardValue.Vacuum(value.state, value.battery)
    }

    /** 0..255 → 0..100. */
    fun percent(brightness: Int): Int = (brightness * 100 / 255.0).roundToInt()

    fun number(value: Double, locale: Locale): String =
        NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = 1
            minimumFractionDigits = 0
        }.format(value)
}
