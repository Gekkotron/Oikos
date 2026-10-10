package com.gekkotron.oikos.feature.home.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.icons.DoorIcon
import com.gekkotron.oikos.designsystem.icons.GarageIcon
import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.designsystem.icons.ShutterIcon
import com.gekkotron.oikos.designsystem.icons.StaticIcon
import com.gekkotron.oikos.designsystem.icons.WindowIcon
import com.gekkotron.oikos.feature.home.presentation.CardUi
import com.gekkotron.oikos.feature.home.presentation.CardValue
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import kotlin.math.abs
import kotlin.math.roundToInt

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
    Kind.COVER -> (card.value as? CardValue.CoverGroup)?.let { g ->
        IconSpec.Shutter(g.positions.average().toFloat() / 100f, g.moving.takeIf { card.available })
    } ?: shutter(card.value as? CardValue.Cover, card.available)
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
    Kind.VACUUM -> IconSpec.Static(OikosGlyph.VACUUM, on = (card.state as? StateValue.Vacuum)?.state == "cleaning")
}

/** An unavailable cover keeps its last known position (closed could misreport it) but stops moving. */
private fun shutter(v: CardValue.Cover?, available: Boolean): IconSpec.Shutter {
    val target = when {
        v == null -> null
        v.position != null -> v.position.coerceIn(0, 100) / 100f
        v.state == CoverState.OPEN -> 1f
        v.state == CoverState.CLOSED -> 0f
        else -> null
    }
    val moving = v?.state?.takeIf { available && (it == CoverState.OPENING || it == CoverState.CLOSING) }
    return IconSpec.Shutter(target, moving)
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

internal const val SWING_MS = 500
internal const val SHUTTER_MS = 600
internal const val DRIFT_FULL_MS = 8000
private val SWING_EASING = CubicBezierEasing(0.3f, 0.7f, 0.2f, 1f)
private val SHUTTER_EASING = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)

/** Whether the spec carries a real state; a cached card has none until live data arrives. */
private fun IconSpec.known(): Boolean = when (this) {
    is IconSpec.Shutter -> target != null
    is IconSpec.Swing -> open != null
    is IconSpec.Static -> true
}

private fun IconSpec.target(): Float? = when (this) {
    is IconSpec.Shutter -> target
    is IconSpec.Swing -> if (open == true) 1f else 0f
    is IconSpec.Static -> null
}

/**
 * The drawn fraction for [spec] (spec §3): 0 closed … 1 open. The first composition draws the state at
 * once; later changes tween from wherever the drawing is, so a flapping contact turns around instead of
 * jumping. A moving shutter drifts toward its end stop at [DRIFT_FULL_MS] per full travel. With
 * [animate] false every change snaps and nothing drifts.
 */
@Composable
internal fun rememberIconProgress(spec: IconSpec, animate: Boolean): State<Float> {
    val progress = remember { Animatable(spec.target() ?: 0f) }
    val settled = remember { BooleanArray(1) { spec.known() } }
    LaunchedEffect(spec, animate) {
        val target = spec.target()
        // The first real state after a cold start (cached card, no state yet) is drawn at once, like a first composition.
        if (!settled[0] && spec.known()) {
            settled[0] = true
            if (target != null) progress.snapTo(target)
            if (spec !is IconSpec.Shutter || spec.moving == null) return@LaunchedEffect
        }
        if (!animate) {
            if (target != null) progress.snapTo(target)
            return@LaunchedEffect
        }
        when (spec) {
            is IconSpec.Swing -> progress.animateTo(target ?: 0f, tween(SWING_MS, easing = SWING_EASING))
            is IconSpec.Shutter -> {
                // animateTo waits out its whole duration even with nothing to travel, which would delay the drift.
                if (target != null && target != progress.value) progress.animateTo(target, tween(SHUTTER_MS, easing = SHUTTER_EASING))
                val end = when (spec.moving) {
                    CoverState.OPENING -> 1f
                    CoverState.CLOSING -> 0f
                    else -> return@LaunchedEffect
                }
                val ms = (abs(end - progress.value) * DRIFT_FULL_MS).roundToInt()
                if (ms > 0) progress.animateTo(end, tween(ms, easing = LinearEasing))
            }
            is IconSpec.Static -> Unit
        }
    }
    return progress.asState()
}

/** The card icon, decorative: hidden from TalkBack, the card's own description carries the state. */
@Composable
internal fun KindIcon(spec: IconSpec, modifier: Modifier = Modifier) {
    val animate = animationsEnabled()
    val progress by rememberIconProgress(spec, animate)
    val c = OikosTheme.colors
    val neutral = LocalContentColor.current
    val m = modifier.clearAndSetSemantics { }
    when (spec) {
        is IconSpec.Shutter -> ShutterIcon(progress, m)
        is IconSpec.Swing -> {
            // Follows the swing itself, mint closed to amber open: it snaps when the swing snaps, and a theme switch never fades.
            val accent = if (spec.open == null) neutral else lerp(c.connected, c.warning, progress)
            when (spec.shape) {
                SwingShape.DOOR -> DoorIcon(progress, accent, m)
                SwingShape.WINDOW -> WindowIcon(progress, accent, m)
                SwingShape.GARAGE -> GarageIcon(progress, accent, m)
            }
        }
        // Warm Minimal's on-card is espresso: its dark amber would vanish, so a lit glyph uses the card's content color there.
        is IconSpec.Static -> StaticIcon(spec.glyph, if (spec.on) (if (c.isDark) c.warning else neutral) else null, m)
    }
}

@Composable
private fun animationsEnabled(): Boolean {
    if (LocalInspectionMode.current) return true
    val resolver = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
}
