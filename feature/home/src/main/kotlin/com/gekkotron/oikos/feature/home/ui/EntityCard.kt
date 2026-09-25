package com.gekkotron.oikos.feature.home.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.components.OikosCard
import com.gekkotron.oikos.designsystem.components.PendingDot
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.feature.home.presentation.CardUi
import com.gekkotron.oikos.feature.home.presentation.TapAction

@Composable
internal fun EntityCard(card: CardUi, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val valueText = if (card.available) cardValueText(card.value) else stringResource(R.string.value_unavailable)
    val room = card.areaName ?: stringResource(R.string.area_unassigned)
    val description = stringResource(if (card.hidden) R.string.card_description_hidden else R.string.card_description, card.name, valueText, room)
    val onText = stringResource(R.string.value_on)
    val offText = stringResource(R.string.value_off)
    val toggle = card.tap == TapAction.TOGGLE
    OikosCard(
        modifier = modifier
            .testTag("card-${card.id}")
            .alpha(if (card.hidden) 0.6f else 1f)
            .semantics {
                contentDescription = description
                if (toggle) stateDescription = if (card.on) onText else offText
            },
        on = card.on,
        enabled = card.available,
        onClick = if (card.tap != TapAction.NONE) onClick else null,
        onLongClick = onLongClick,
        onLongClickLabel = stringResource(if (card.hasSheet) R.string.action_controls else R.string.action_customize),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(kindGlyph(card.kind, card.deviceClass), fontSize = 20.sp, modifier = Modifier.clearAndSetSemantics { })
            Spacer(Modifier.weight(1f))
            if (card.pending) PendingDot()
        }
        Spacer(Modifier.height(8.dp))
        Text(
            card.name,
            style = OikosTheme.type.cardName,
            color = LocalContentColor.current.copy(alpha = 0.72f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(valueText, style = OikosTheme.type.cardValue)
    }
}
