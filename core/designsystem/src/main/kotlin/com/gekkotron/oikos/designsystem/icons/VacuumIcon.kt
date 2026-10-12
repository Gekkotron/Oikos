package com.gekkotron.oikos.designsystem.icons

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser

/** What the vacuum tile is showing (spec §2.6): still, actively cleaning, or heading home. */
enum class VacuumMotion { IDLE, CLEANING, RETURNING }

/**
 * The home-tile vacuum. The body stays put; the side brush spins while active and dust puffs trail
 * from it while cleaning; the whole robot slides toward a tiny dock on the right while returning.
 * [accent] lights the robot (uses the card's content color otherwise); the dock always stays neutral.
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
    IconCanvas(modifier) { neutral ->
        val tint = accent ?: neutral
        if (motion == VacuumMotion.RETURNING) drawDock(neutral)
        translate(left = robotShift) {
            drawBody(tint)
            drawDome(tint)
            if (motion == VacuumMotion.CLEANING && animate) drawDust(tint, dustPhase)
            drawBrush(tint, brushAngle)
        }
    }
}

@Composable
private fun brushAngle(animate: Boolean, motion: VacuumMotion, fanSpeed: String?): Float {
    if (motion == VacuumMotion.IDLE || !animate) return 0f
    val transition = rememberInfiniteTransition(label = "vacuum-brush")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(brushPeriodMs(motion, fanSpeed), easing = LinearEasing)),
        label = "angle",
    ).value
}

/** Faster at higher suction while cleaning; a measured spin while returning. */
private fun brushPeriodMs(motion: VacuumMotion, fanSpeed: String?): Int {
    if (motion == VacuumMotion.RETURNING) return 1600
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

@Composable
private fun robotShift(animate: Boolean, motion: VacuumMotion): Float {
    if (motion != VacuumMotion.RETURNING || !animate) return 0f
    val transition = rememberInfiniteTransition(label = "vacuum-shift")
    return transition.animateFloat(
        initialValue = -2f,
        targetValue = 2.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shift",
    ).value
}

/** The chassis: a soft duotone disc. */
private fun DrawScope.drawBody(color: Color) {
    val body = Path().apply { addOval(Rect(Offset(12f, 12f), 7.5f)) }
    surface(body, color)
}

/** The LIDAR dome arc on top: the robot's front. */
private fun DrawScope.drawDome(color: Color) {
    val dome = PathParser().parsePathString("M7.5 8.4a6.3 6.3 0 0 1 9 0").toPath()
    outline(dome, color)
}

/** The side brush: a Y of bristles at (12, 15.5) with a tiny hub, pivoting in place. */
private fun DrawScope.drawBrush(color: Color, angle: Float) {
    rotate(angle, pivot = Offset(12f, 15.5f)) {
        drawLine(color, Offset(12f, 15.5f), Offset(12f, 13.9f), strokeWidth = 0.9f, cap = StrokeCap.Round)
        drawLine(color, Offset(12f, 15.5f), Offset(13.4f, 16.3f), strokeWidth = 0.9f, cap = StrokeCap.Round)
        drawLine(color, Offset(12f, 15.5f), Offset(10.6f, 16.3f), strokeWidth = 0.9f, cap = StrokeCap.Round)
        drawCircle(color, radius = 0.6f, center = Offset(12f, 15.5f))
    }
}

/** Two puffs leaving the brush, 180° out of phase; they shrink and fade as they travel. */
private fun DrawScope.drawDust(color: Color, phase: Float) {
    val from = Offset(12f, 16.2f)
    puff(color, phase, from, Offset(5.5f, 21f))
    puff(color, (phase + 0.5f) % 1f, from, Offset(18.5f, 21f))
}

private fun DrawScope.puff(color: Color, phase: Float, from: Offset, to: Offset) {
    val x = from.x + (to.x - from.x) * phase
    val y = from.y + (to.y - from.y) * phase
    val alpha = ((1f - phase) * 0.9f).coerceIn(0f, 1f)
    val radius = 1f - phase * 0.5f
    drawCircle(color, radius = radius, center = Offset(x, y), alpha = alpha)
}

/** A tiny docking bay on the right edge, open toward the robot. */
private fun DrawScope.drawDock(color: Color) {
    val dock = Path().apply {
        moveTo(22.5f, 9f)
        lineTo(21f, 9f)
        lineTo(21f, 15f)
        lineTo(22.5f, 15f)
    }
    outline(dock, color, width = 1.3f)
}
