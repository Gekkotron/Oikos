package com.gekkotron.oikos.feature.home.ui

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Kind

private val GLYPH_TRAVEL = 3.dp
private const val GLYPH_LOOP_MS = 1000

/**
 * The kind glyph, decorative (hidden from TalkBack; the card's own description already carries the
 * state). While [moving] is [CoverState.OPENING] or [CoverState.CLOSING] it slides up/down in a
 * loop, unless the system has reduced-motion animations off.
 */
@Composable
internal fun KindGlyph(kind: Kind, deviceClass: String?, moving: CoverState?, modifier: Modifier = Modifier) {
    val glyph = kindGlyph(kind, deviceClass)
    if (moving == null || !animationsEnabled()) {
        Text(glyph, fontSize = 20.sp, modifier = modifier.clearAndSetSemantics { })
        return
    }
    val travelPx = with(LocalDensity.current) { GLYPH_TRAVEL.toPx() }
    val direction = if (moving == CoverState.OPENING) -1f else 1f
    val offset by rememberInfiniteTransition(label = "cover-glyph").animateFloat(
        initialValue = 0f,
        targetValue = direction * travelPx,
        animationSpec = infiniteRepeatable(tween(GLYPH_LOOP_MS, easing = LinearEasing), RepeatMode.Reverse),
        label = "cover-glyph-offset",
    )
    Text(glyph, fontSize = 20.sp, modifier = modifier.clearAndSetSemantics { }.graphicsLayer { translationY = offset })
}

@Composable
private fun animationsEnabled(): Boolean {
    if (LocalInspectionMode.current) return true
    val resolver = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
}

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
