package com.gekkotron.oikos.feature.home.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosTheme

/**
 * Water-heater tank, Oikos-os `.heater__tank` ported to Compose: a tall rounded tank with a vertical
 * cold → warm gradient and a heating element in the bottom 28%. Muted and dim while idle, saturated and
 * glowing while heating. Decorative — the card's own description carries the state.
 */
@Composable
internal fun TankGlyph(heating: Boolean, modifier: Modifier = Modifier) {
    val border = OikosTheme.colors.cardBorder
    // Fixed mid-grey so an idle tank looks muted in every theme — lerping toward the theme's `contentMuted`
    // (espresso on Warm Minimal, ink on Night Glass) turns the gradient into mud or ink.
    val neutral = Color(0xFF999999)
    Canvas(
        modifier
            .size(width = 24.dp, height = 40.dp)
            .clearAndSetSemantics { },
    ) {
        val radius = CornerRadius(6.dp.toPx())
        val stroke = 1.5.dp.toPx()
        val full = Size(size.width, size.height)
        // Oikos-os palette: cold blue at the bottom, warm orange at the top.
        val cold = Color(0xFF3B82F6)
        val mid = Color(0xFF8B7FD6)
        val warm = Color(0xFFFF7A3D)
        val saturation = if (heating) 1f else 0.35f
        val alpha = if (heating) 1f else 0.75f
        fun tint(color: Color) = lerp(neutral, color, saturation).copy(alpha = alpha)
        val brush = Brush.verticalGradient(
            0f to tint(warm),
            0.55f to tint(mid),
            1f to tint(cold),
        )
        drawRoundRect(brush = brush, cornerRadius = radius, size = full)

        // Element in the bottom 28%, inset by the stroke so it hugs the inner tank.
        val elementTop = size.height * 0.72f
        val elementHeight = size.height * 0.28f
        val elementLeft = stroke
        val elementWidth = size.width - 2 * stroke
        if (heating) {
            // Warm overlay with a soft halo above it (approximates the CSS box-shadow glow).
            val halo = Color(0xFFFF8C32).copy(alpha = 0.35f)
            drawRoundRect(
                color = halo,
                topLeft = Offset(elementLeft, elementTop - 6.dp.toPx()),
                size = Size(elementWidth, elementHeight + 6.dp.toPx()),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
            val element = Color(0xFFFF963C).copy(alpha = 0.75f)
            drawRoundRect(
                color = element,
                topLeft = Offset(elementLeft, elementTop),
                size = Size(elementWidth, elementHeight),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        } else {
            // Idle: faint horizontal stripes hint at the element without competing with other cards.
            val stripe = neutral.copy(alpha = 0.22f)
            val step = 3.dp.toPx()
            var y = elementTop + step * 0.5f
            while (y < elementTop + elementHeight) {
                drawLine(
                    color = stripe,
                    start = Offset(elementLeft, y),
                    end = Offset(elementLeft + elementWidth, y),
                    strokeWidth = 1.dp.toPx(),
                )
                y += step
            }
        }

        drawRoundRect(
            color = border,
            style = Stroke(width = stroke),
            cornerRadius = radius,
            size = full,
        )
    }
}
