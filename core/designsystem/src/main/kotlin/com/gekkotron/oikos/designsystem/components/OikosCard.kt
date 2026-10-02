package com.gekkotron.oikos.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosColors
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme

/** Enter, DPad-center and the numpad Enter all activate a focused long-press-only card. */
private val LONG_PRESS_KEYS = setOf(Key.Enter, Key.DirectionCenter, Key.NumPadEnter)

/**
 * The base card: glass (Night Glass) or paper (Warm Minimal); `on` switches to the amber gradient or
 * the inverted card. Height is a minimum only, so text can grow at 200 % font scale (spec §5.3).
 */
@Composable
fun OikosCard(
    modifier: Modifier = Modifier,
    on: Boolean = false,
    /** A warm border without the "on" gradient: an open door or window (device-icons spec §2). */
    accent: Boolean = false,
    /** The border colour when [accent] is set; amber by default. Active danger rows pass rose. */
    accentColor: Color? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    /** Read by TalkBack as the long-press action ("Customize", "Controls"). */
    onLongClickLabel: String? = null,
    /** Dense lists (Security tiles) go lower; still a minimum, so large font scales grow the card. */
    minHeight: Dp = OikosDimens.cardMinHeight,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = OikosTheme.colors
    val shape = RoundedCornerShape(OikosDimens.cardRadius)
    val background = if (on) Brush.linearGradient(listOf(c.cardOnStart, c.cardOnEnd)) else SolidColor(c.card)
    val haptics = LocalHapticFeedback.current
    val longClick by rememberUpdatedState(onLongClick)
    val focusInteractionSource = remember { MutableInteractionSource() }
    val focused by focusInteractionSource.collectIsFocusedAsState()
    // `enabled` gates the tap only: an unavailable device must still be long-pressable, to hide or rename it.
    val clicks = when {
        onClick != null && enabled ->
            Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = onLongClickLabel)
        onLongClick != null -> Modifier
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    longClick?.invoke()
                })
            }
            // No tap, so this is the only way D-pad, keyboard or Switch Access users reach it.
            .focusable(interactionSource = focusInteractionSource)
            .then(if (focused) Modifier.border(2.dp, c.cardOnBorder, shape) else Modifier)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key in LONG_PRESS_KEYS) {
                    longClick?.invoke()
                    true
                } else {
                    false
                }
            }
            // `this.` picks the semantics action, not the nullable `onLongClick` parameter of the same name.
            .semantics { this.onLongClick(label = onLongClickLabel) { longClick?.invoke(); true } }
        onClick != null -> Modifier.combinedClickable(enabled = false, onClick = onClick)
        else -> Modifier
    }
    CompositionLocalProvider(LocalContentColor provides if (on) c.contentOnCardOn else c.content) {
        Column(
            modifier
                .alpha(if (enabled) 1f else 0.45f)
                .then(if (c.isDark) Modifier else Modifier.shadow(6.dp, shape, ambientColor = c.shadow, spotColor = c.shadow))
                .clip(shape)
                .background(background)
                .border(1.dp, cardBorderColor(c, on, accent, accentColor), shape)
                .then(clicks)
                .heightIn(min = minHeight)
                .padding(OikosDimens.cardPadding),
            verticalArrangement = Arrangement.SpaceBetween,
            content = content,
        )
    }
}

internal fun cardBorderColor(colors: OikosColors, on: Boolean, accent: Boolean, accentColor: Color? = null): Color = when {
    on -> colors.cardOnBorder
    accent -> (accentColor ?: colors.warning).copy(alpha = 0.35f)
    else -> colors.cardBorder
}
