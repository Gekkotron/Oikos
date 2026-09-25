package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.model.EnergyRole
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
        )
    }
}
