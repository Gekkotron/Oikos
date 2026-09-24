package com.gekkotron.oikos.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.ui.unit.dp

/** Spec §5.1 shared tokens: 4 dp grid, 20/24 dp radii, 48 dp touch targets. */
object OikosDimens {
    val cardRadius = 20.dp
    val navRadius = 24.dp
    val cardPadding = 12.dp
    val cardMinHeight = 88.dp
    val gridGap = 8.dp
    val minTouch = 48.dp
}

/** Spec §5.1: 200–350 ms with emphasized easing. */
object OikosMotion {
    const val SHORT = 200
    const val MEDIUM = 350
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}
