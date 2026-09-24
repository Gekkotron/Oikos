package com.gekkotron.oikos.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme

/**
 * The base card: glass (Night Glass) or paper (Warm Minimal); `on` switches to the amber gradient or
 * the inverted card. Height is a minimum only, so text can grow at 200 % font scale (spec §5.3).
 */
@Composable
fun OikosCard(
    modifier: Modifier = Modifier,
    on: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = OikosTheme.colors
    val shape = RoundedCornerShape(OikosDimens.cardRadius)
    val background = if (on) Brush.linearGradient(listOf(c.cardOnStart, c.cardOnEnd)) else SolidColor(c.card)
    val clicks = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(enabled = enabled, onClick = onClick ?: {}, onLongClick = onLongClick)
    } else {
        Modifier
    }
    CompositionLocalProvider(LocalContentColor provides if (on) c.contentOnCardOn else c.content) {
        Column(
            modifier
                .alpha(if (enabled) 1f else 0.45f)
                .then(if (c.isDark) Modifier else Modifier.shadow(6.dp, shape, ambientColor = c.shadow, spotColor = c.shadow))
                .clip(shape)
                .background(background)
                .border(1.dp, if (on) c.cardOnBorder else c.cardBorder, shape)
                .then(clicks)
                .heightIn(min = OikosDimens.cardMinHeight)
                .padding(OikosDimens.cardPadding),
            verticalArrangement = Arrangement.SpaceBetween,
            content = content,
        )
    }
}
