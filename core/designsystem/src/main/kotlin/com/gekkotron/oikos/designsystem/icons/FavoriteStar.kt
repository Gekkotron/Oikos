package com.gekkotron.oikos.designsystem.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** The favorite affordance: a solid-filled star when on, an outline when off. [accent] tints the filled state. */
@Composable
fun FavoriteStar(filled: Boolean, accent: Color? = null, modifier: Modifier = Modifier) = IconCanvas(modifier) { neutral ->
    val (path, _) = OikosGlyph.STAR.paths.first()
    if (filled) {
        val tint = accent ?: neutral
        drawPath(path, tint)
        outline(path, tint)
    } else {
        outline(path, neutral)
    }
}
