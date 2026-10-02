package com.gekkotron.oikos.feature.home.ui

import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.feature.home.presentation.CardUi
import com.gekkotron.oikos.feature.home.presentation.CardValue
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Kind

internal enum class SwingShape { DOOR, WINDOW, GARAGE }

/** What a card's icon draws (device-icons spec §4.3), before animation. */
internal sealed interface IconSpec {
    /** [target] null: no position and not OPEN/CLOSED, keep the drawn value. */
    data class Shutter(val target: Float?, val moving: CoverState?) : IconSpec

    /** [open] null: unknown or unavailable, drawn neutral and closed. */
    data class Swing(val shape: SwingShape, val open: Boolean?) : IconSpec

    data class Static(val glyph: OikosGlyph, val on: Boolean) : IconSpec
}

/** An open door, window or garage door: the card gets the accent border. */
internal val IconSpec.openContact: Boolean get() = this is IconSpec.Swing && open == true

internal fun iconSpec(card: CardUi): IconSpec = when (card.kind) {
    Kind.COVER -> shutter(card.value as? CardValue.Cover)
    Kind.BINARY_SENSOR -> swingShape(card.deviceClass)
        ?.let { IconSpec.Swing(it, if (card.available) (card.value as? CardValue.Contact)?.on else null) }
        ?: IconSpec.Static(binaryGlyph(card.deviceClass), on = false)
    Kind.LIGHT -> IconSpec.Static(OikosGlyph.BULB, card.on)
    Kind.SWITCH -> IconSpec.Static(OikosGlyph.PLUG, card.on)
    Kind.CLIMATE -> IconSpec.Static(OikosGlyph.SNOWFLAKE, on = false)
    Kind.SENSOR -> IconSpec.Static(sensorGlyph(card.deviceClass), on = false)
    Kind.ALARM_PANEL -> IconSpec.Static(OikosGlyph.SHIELD, on = false)
    Kind.NUMBER -> IconSpec.Static(OikosGlyph.SLIDER, on = false)
    Kind.SELECT -> IconSpec.Static(OikosGlyph.LIST, on = false)
    Kind.BUTTON -> IconSpec.Static(OikosGlyph.PLAY, on = false)
}

private fun shutter(v: CardValue.Cover?): IconSpec.Shutter {
    val target = when {
        v == null -> null
        v.position != null -> v.position.coerceIn(0, 100) / 100f
        v.state == CoverState.OPEN -> 1f
        v.state == CoverState.CLOSED -> 0f
        else -> null
    }
    return IconSpec.Shutter(target, v?.state?.takeIf { it == CoverState.OPENING || it == CoverState.CLOSING })
}

private fun swingShape(deviceClass: String?) = when (deviceClass) {
    "door", "opening" -> SwingShape.DOOR
    "window" -> SwingShape.WINDOW
    "garage_door" -> SwingShape.GARAGE
    else -> null
}

private fun sensorGlyph(deviceClass: String?) = when (deviceClass) {
    "temperature" -> OikosGlyph.THERMOMETER
    "humidity", "moisture" -> OikosGlyph.DROP
    "power", "energy" -> OikosGlyph.BOLT
    else -> OikosGlyph.TREND
}

private fun binaryGlyph(deviceClass: String?) = when (deviceClass) {
    "motion", "occupancy", "presence" -> OikosGlyph.WALKER
    "smoke" -> OikosGlyph.FLAME
    "moisture" -> OikosGlyph.DROP
    else -> OikosGlyph.DOT
}
