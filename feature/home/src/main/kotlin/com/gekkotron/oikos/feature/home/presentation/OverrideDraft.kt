package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.LightOnPreset
import com.gekkotron.oikos.model.UiOverride

/**
 * The override sheet's fields (spec §5.2). Only the fields the user changed replace the card's
 * override. So opening and saving never turns a suggested_area into an explicit area, and a
 * `cardStyle` this app does not know survives on kinds whose sheet does not show it.
 */
data class OverrideDraft(
    val name: String,
    val areaId: String,
    val favorite: Boolean,
    val hidden: Boolean,
    /** Text, blank = automatic. */
    val order: String,
    val cardStyle: String?,
    val energyRole: EnergyRole?,
    /** Only kept for a [EnergyRole.signed] role. */
    val energyInvert: Boolean,
    val securityHidden: Boolean = false,
    val homePinned: Boolean = false,
    val securityAction: Boolean = false,
    val securityAsk: Boolean = true,
    /** Off = this cover or light leaves its room's group (room groups spec §6). */
    val grouped: Boolean = true,
    /** On = this switch is shown as a light (switch-as-light). */
    val asLight: Boolean = false,
    /** A named preset the tile uses when turning the light on, or null to resume the last state. */
    val lightOnPreset: String? = null,
    /** Overrides the preset's baked brightness (1..255); null keeps the preset default. */
    val lightOnBrightness: Int? = null,
    /** Raw text for the climate quick-target chips; parsed by [com.gekkotron.oikos.model.ClimatePresets.parse]. */
    val climatePresets: String = "",
) {
    val orderValid: Boolean
        get() = order.isBlank() || order.trim().let { t -> t.all(Char::isDigit) && t.toIntOrNull()?.let { it in 0..MAX_ORDER } == true }

    fun applyTo(card: CardUi): UiOverride {
        require(orderValid) { "invalid position '$order'" }
        val start = from(card)
        val ui = card.ui
        val typed = name.trim()
        return ui.copy(
            name = when {
                typed == start.name -> ui.name
                typed.isEmpty() -> null
                else -> typed
            },
            area = if (areaId == start.areaId) ui.area else areaId,
            favorite = favorite,
            hidden = hidden,
            order = if (order.trim() == start.order) ui.order else order.trim().takeIf { it.isNotEmpty() }?.toInt(),
            cardStyle = cardStyle,
            energyRole = energyRole,
            energyInvert = energyInvert && energyRole?.signed == true,
            securityHidden = securityHidden,
            homePinned = homePinned,
            securityAction = securityAction,
            securityAsk = securityAsk,
            ungrouped = !grouped,
            asLight = asLight,
            lightOnPreset = lightOnPreset?.takeIf { LightOnPreset.of(it) != null },
            lightOnBrightness = lightOnBrightness?.takeIf { lightOnPreset != null && it in 1..255 },
            climatePresets = climatePresets.trim().takeIf { it.isNotEmpty() },
        )
    }

    companion object {
        const val MAX_ORDER = 9999

        fun from(card: CardUi) = OverrideDraft(
            name = card.name,
            areaId = card.areaId,
            favorite = card.ui.favorite,
            hidden = card.ui.hidden,
            order = card.ui.order?.toString() ?: "",
            cardStyle = card.ui.cardStyle,
            energyRole = card.ui.energyRole,
            energyInvert = card.ui.energyInvert,
            securityHidden = card.ui.securityHidden,
            homePinned = card.ui.homePinned,
            securityAction = card.ui.securityAction,
            securityAsk = card.ui.securityAsk,
            grouped = !card.ui.ungrouped,
            asLight = card.ui.asLight,
            lightOnPreset = card.ui.lightOnPreset,
            lightOnBrightness = card.ui.lightOnBrightness,
            climatePresets = card.ui.climatePresets.orEmpty(),
        )
    }
}
