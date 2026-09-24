package com.gekkotron.oikos.feature.home.ui

import com.gekkotron.oikos.model.Kind

/** Emoji glyphs, as in the mockups, until the real icon set lands. */
internal fun kindGlyph(kind: Kind, deviceClass: String?): String = when (kind) {
    Kind.LIGHT -> "💡"
    Kind.SWITCH -> "🔌"
    Kind.COVER -> "🪟"
    Kind.CLIMATE -> "❄️"
    Kind.SENSOR -> when (deviceClass) {
        "temperature" -> "🌡️"
        "humidity", "moisture" -> "💧"
        "power", "energy" -> "⚡"
        else -> "📈"
    }
    Kind.BINARY_SENSOR -> when (deviceClass) {
        "door", "garage_door", "opening" -> "🚪"
        "window" -> "🪟"
        "motion", "occupancy", "presence" -> "🚶"
        "smoke" -> "🔥"
        "moisture" -> "💧"
        else -> "⚪"
    }
    Kind.ALARM_PANEL -> "🛡️"
    Kind.NUMBER -> "🎚️"
    Kind.SELECT -> "📋"
    Kind.BUTTON -> "▶️"
}
