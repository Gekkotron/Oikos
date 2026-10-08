package com.gekkotron.oikos.feature.energy.presentation

import java.text.NumberFormat
import java.util.Locale

/** Energy screen values: kW with one decimal from 1000 W, whole watts below (spec §4). */
object EnergyFormat {
    fun power(watts: Double, locale: Locale): String =
        if (watts >= 1_000) "${number(watts / 1_000, locale, 1, 1)} kW" else "${number(watts, locale, 0, 0)} W"

    /** The number part of [power], for the spoken "%s kilowatts" / "%s watts" strings. */
    fun spokenNumber(watts: Double, locale: Locale): String =
        if (watts >= 1_000) number(watts / 1_000, locale, 1, 1) else number(watts, locale, 0, 0)

    /** A lifetime energy counter: "1,234.5 kWh". */
    fun energy(kwh: Double, locale: Locale): String = "${number(kwh, locale, 0, 1)} kWh"

    fun amount(value: Double, unit: String?, locale: Locale): String =
        listOfNotNull(number(value, locale, 0, 1), unit?.takeIf { it.isNotBlank() }).joinToString(" ")

    /** A meter index, to the litre when in m³: "601.359 m³". */
    fun meter(value: Double, unit: String?, locale: Locale): String =
        listOfNotNull(number(value, locale, 0, 3), unit?.takeIf { it.isNotBlank() }).joinToString(" ")

    private fun number(value: Double, locale: Locale, min: Int, max: Int): String =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = min
            maximumFractionDigits = max
        }.format(value)
}
