package com.gekkotron.oikos.feature.home.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import kotlin.math.abs
import kotlin.math.roundToInt
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

internal const val SWING_MS = 500
internal const val SHUTTER_MS = 600
internal const val DRIFT_FULL_MS = 8000
private val SWING_EASING = CubicBezierEasing(0.3f, 0.7f, 0.2f, 1f)
private val SHUTTER_EASING = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)

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
    LaunchedEffect(spec, animate) {
        val target = spec.target()
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
