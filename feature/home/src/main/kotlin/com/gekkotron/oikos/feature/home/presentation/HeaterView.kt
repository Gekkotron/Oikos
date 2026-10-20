package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.StateValue
import kotlin.math.roundToInt

/**
 * What a water heater card shows beyond its temperature, ported from Oikos-os `HeaterView::from_state`
 * (crates/oikos-web/src/cards/water_heater.rs). [heating] is also the card highlight: retune it here only.
 */
data class HeaterView(
    /** `power` above zero, or without `power` a mode other than "off". */
    val heating: Boolean,
    /** The average heating power, 0–100 %, from the numeric `power` attribute. */
    val power: Int?,
    /** The cold-water inlet temperature, from the numeric `inlet_temperature` attribute. */
    val inlet: Double?,
    /** The activity chip; null when there is nothing to say. */
    val chip: HeaterChip?,
) {
    companion object {
        fun of(state: EntityState?): HeaterView {
            val mode = (state?.value as? StateValue.Climate)?.mode
            val attributes = state?.attributes.orEmpty()
            // Clamped first, so roundToInt (half up) matches Rust's half-away-from-zero.
            val power = finite(attributes["power"])?.coerceIn(0.0, 100.0)?.roundToInt()
            val inlet = finite(attributes["inlet_temperature"])
            val (heating, chip) = when {
                power != null && power > 0 -> true to HeaterChip.Heating(power)
                power != null -> false to HeaterChip.Idle
                mode == null -> false to null
                mode.equals("off", ignoreCase = true) -> false to HeaterChip.Off
                else -> true to HeaterChip.Mode(mode)
            }
            return HeaterView(heating, power, inlet, chip)
        }

        private fun finite(v: Any?): Double? = (v as? Number)?.toDouble()?.takeIf { it.isFinite() }
    }
}

sealed interface HeaterChip {
    data class Heating(val power: Int) : HeaterChip
    data object Idle : HeaterChip
    data object Off : HeaterChip
    data class Mode(val mode: String) : HeaterChip
}
