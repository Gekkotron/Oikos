package com.gekkotron.oikos.designsystem.icons

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate

/** What the vacuum tile is showing (spec §2.6): still, actively cleaning, or heading home. */
enum class VacuumMotion { IDLE, CLEANING, RETURNING }

/**
 * The home-tile vacuum. The chassis stays still; the side brush at the robot's front-right spins
 * while active and dust puffs flick out behind it while cleaning; the whole robot slides toward a
 * small home base at the right edge while returning. [accent] lights the robot (uses the card's
 * content color otherwise); the base always stays neutral.
 */
@Composable
fun VacuumIcon(
    motion: VacuumMotion,
    fanSpeed: String? = null,
    accent: Color? = null,
    animate: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val brushAngle = brushAngle(animate, motion, fanSpeed)
    val dustPhase = dustPhase(animate, motion)
    val robotShift = robotShift(animate, motion)
    val robotAngle = robotAngle(animate, motion)
    val chassisAlpha = chassisAlpha(animate, motion, fanSpeed)
    IconCanvas(modifier) { neutral ->
        val tint = accent ?: neutral
        if (motion == VacuumMotion.RETURNING) drawBase(neutral)
        translate(left = robotShift) {
            rotate(robotAngle, pivot = CHASSIS_CENTER) {
                drawChassis(tint, chassisAlpha)
                drawDome(tint)
                if (motion == VacuumMotion.CLEANING && animate) drawDust(tint, dustPhase)
                drawBrush(tint, brushAngle)
            }
        }
    }
}

private val CHASSIS_CENTER = Offset(12f, 12f)

@Composable
private fun brushAngle(animate: Boolean, motion: VacuumMotion, fanSpeed: String?): Float {
    // Brush only spins while actually cleaning; during returning the whole chassis turns instead.
    if (motion != VacuumMotion.CLEANING || !animate) return 0f
    val transition = rememberInfiniteTransition(label = "vacuum-brush")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(brushPeriodMs(fanSpeed), easing = LinearEasing)),
        label = "angle",
    ).value
}

/** Faster at higher suction while cleaning. */
private fun brushPeriodMs(fanSpeed: String?): Int {
    return when (fanSpeed?.lowercase()) {
        "silent", "silence", "quiet", "eco", "low" -> 1100
        "turbo", "high" -> 600
        "max", "max+", "max_plus" -> 450
        else -> 800
    }
}

@Composable
private fun dustPhase(animate: Boolean, motion: VacuumMotion): Float {
    if (motion != VacuumMotion.CLEANING || !animate) return 0f
    val transition = rememberInfiniteTransition(label = "vacuum-dust")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1200, easing = LinearEasing)),
        label = "phase",
    ).value
}

/** Chassis fill breathes while cleaning so even a frozen frame reads as "working". */
@Composable
private fun chassisAlpha(animate: Boolean, motion: VacuumMotion, fanSpeed: String?): Float {
    val base = 0.22f
    if (motion != VacuumMotion.CLEANING || !animate) return base
    val transition = rememberInfiniteTransition(label = "vacuum-pulse")
    return transition.animateFloat(
        initialValue = base,
        targetValue = 0.38f,
        animationSpec = infiniteRepeatable(
            animation = tween(brushPeriodMs(fanSpeed), easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    ).value
}

/** Returning: the robot turns once so its front (brush) faces the dock, then holds while sliding home. */
@Composable
private fun robotAngle(animate: Boolean, motion: VacuumMotion): Float {
    if (motion != VacuumMotion.RETURNING || !animate) return 0f
    val transition = rememberInfiniteTransition(label = "vacuum-robot")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 90f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = SHIFT_CYCLE_MS
                0f at 0 using FastOutSlowInEasing     // start of the turn
                90f at TURN_MS                        // chassis top now points east, at the dock
                90f at SHIFT_CYCLE_MS                 // held throughout the slide
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "angle",
    ).value
}

/** Returning: robot waits at the left while it turns, then slides left-to-right until it reaches the station. */
@Composable
private fun robotShift(animate: Boolean, motion: VacuumMotion): Float {
    if (motion != VacuumMotion.RETURNING || !animate) return 0f
    val transition = rememberInfiniteTransition(label = "vacuum-shift")
    return transition.animateFloat(
        initialValue = -1.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = SHIFT_CYCLE_MS
                -1.5f at 0 using LinearEasing          // held while the robot turns
                -1.5f at TURN_MS using FastOutSlowInEasing
                1.5f at SLIDE_END_MS                   // reached the station
                1.5f at SHIFT_CYCLE_MS                 // held there until the loop restarts
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "shift",
    ).value
}

private const val TURN_MS = 500
private const val SLIDE_END_MS = 2500
private const val SHIFT_CYCLE_MS = 3000

/** The chassis: a soft duotone disc filling most of the canvas so the vacuum reads at Daikin-fan weight. */
private fun DrawScope.drawChassis(color: Color, fillAlpha: Float) {
    val chassis = Path().apply { addOval(Rect(CHASSIS_CENTER, 8f)) }
    drawPath(chassis, color, alpha = fillAlpha)
    outline(chassis, color)
}

/** The LIDAR puck: a filled inner disc sitting inside the chassis, like a real top-down Roborock. */
private fun DrawScope.drawDome(color: Color) {
    val puck = Path().apply { addOval(Rect(Offset(12f, 10f), 2.6f)) }
    drawPath(puck, color, alpha = 0.55f)
    outline(puck, color, width = 1.2f)
}

/** The side brush: three chunky paddles mounted at the chassis top-right edge, so bristles stick out. */
private fun DrawScope.drawBrush(color: Color, angle: Float) {
    val pivot = Offset(17f, 5.5f)
    rotate(angle, pivot = pivot) {
        drawLine(color, pivot, pivot + Offset(0f, -1.5f), strokeWidth = 1.5f, cap = StrokeCap.Round)
        drawLine(color, pivot, pivot + Offset(1.3f, 0.75f), strokeWidth = 1.5f, cap = StrokeCap.Round)
        drawLine(color, pivot, pivot + Offset(-1.3f, 0.75f), strokeWidth = 1.5f, cap = StrokeCap.Round)
    }
    // The hub screw, static so a frozen frame still reads as a brush.
    drawCircle(color, radius = 0.65f, center = pivot)
}

/** Two dust streaks falling vertically past the robot's right side, 180° out of phase (DomoticApp style). */
private fun DrawScope.drawDust(color: Color, phase: Float) {
    puff(color, phase, x = 18f, yStart = 5f, yEnd = 23f)
    puff(color, (phase + 0.5f) % 1f, x = 20f, yStart = 5f, yEnd = 23f)
}

private fun DrawScope.puff(color: Color, phase: Float, x: Float, yStart: Float, yEnd: Float) {
    val y = yStart + (yEnd - yStart) * phase
    // Fast fade-in then long fade-out, so each streak reads as a distinct falling grain.
    val alpha = (if (phase < 0.15f) phase / 0.15f else 1f - (phase - 0.15f) / 0.85f).coerceIn(0f, 1f) * 0.85f
    val radius = 0.9f + 0.4f * phase
    drawCircle(color, radius = radius, center = Offset(x, y), alpha = alpha)
}

/** The charging station: a slim rounded pedestal with a lightning bolt inside — reads as a charger. */
private fun DrawScope.drawBase(color: Color) {
    val pedestal = Path().apply {
        addRoundRect(RoundRect(21.5f, 7.5f, 23.5f, 18.5f, CornerRadius(0.9f)))
    }
    surface(pedestal, color)
    // Lightning bolt: the universal "charging" cue, centered on the pedestal (mid-line y=13).
    val bolt = Path().apply {
        moveTo(22.9f, 9.5f)
        lineTo(22f, 13f)
        lineTo(22.8f, 13f)
        lineTo(22.1f, 16.5f)
        lineTo(23.1f, 12.6f)
        lineTo(22.3f, 12.6f)
        close()
    }
    drawPath(bolt, color, alpha = 0.95f)
}
