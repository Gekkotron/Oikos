package com.gekkotron.oikos.feature.home.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.components.OikosCard
import com.gekkotron.oikos.designsystem.components.PendingDot
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.feature.home.presentation.CardUi
import com.gekkotron.oikos.feature.home.presentation.CardValue
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Kind

/** Room groups spec §3: one wide card for a room's shutters or lights. */
@Composable
internal fun GroupCard(card: CardUi, onClick: () -> Unit, onLongClick: () -> Unit, onSendGroup: (String, Command) -> Unit, modifier: Modifier = Modifier) {
    val name = groupName(card)
    val valueText = if (card.available) cardValueText(card.value) else stringResource(R.string.value_unavailable)
    val room = card.areaName ?: stringResource(R.string.area_unassigned)
    val description = stringResource(R.string.card_description, name, valueText, room)
    val onText = stringResource(R.string.value_on)
    val offText = stringResource(R.string.value_off)
    val staleText = stringResource(R.string.card_stale)
    OikosCard(
        modifier = modifier
            .testTag("card-${card.id}")
            .alpha(if (card.stale) 0.6f else 1f)
            .semantics {
                contentDescription = description
                if (card.kind == Kind.LIGHT) stateDescription = if (card.on) onText else offText
                if (card.stale) stateDescription = staleText
            },
        on = card.on,
        accent = false,
        enabled = card.available,
        onClick = onClick,
        onLongClick = onLongClick,
        onLongClickLabel = stringResource(if (card.kind == Kind.COVER) R.string.action_customize else R.string.action_controls),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindIcon(iconSpec(card))
            Spacer(Modifier.weight(1f))
            if (card.pending) PendingDot()
            Text(
                stringResource(R.string.group_badge, card.members.size),
                style = OikosTheme.type.label,
                color = OikosTheme.colors.warning,
                modifier = Modifier.padding(start = 8.dp)
                    .background(OikosTheme.colors.warningContainer, RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = OikosTheme.type.cardName, color = LocalContentColor.current.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(valueText, style = OikosTheme.type.cardValue)
            }
            if (card.kind == Kind.COVER) CoverButtons(card, onSendGroup)
        }
        (card.value as? CardValue.CoverGroup)?.let { MiniBars(it.positions) }
    }
}

@Composable
private fun CoverButtons(card: CardUi, onSendGroup: (String, Command) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        GroupButton("▲", stringResource(R.string.action_group_open), "group-open-${card.id}", card.available) { onSendGroup(card.id, Command.Open) }
        GroupButton("■", stringResource(R.string.action_group_stop), "group-stop-${card.id}", card.available) { onSendGroup(card.id, Command.Stop) }
        GroupButton("▼", stringResource(R.string.action_group_close), "group-close-${card.id}", card.available) { onSendGroup(card.id, Command.Close) }
    }
}

@Composable
private fun GroupButton(glyph: String, label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(OikosDimens.minTouch).testTag(tag).semantics { contentDescription = label },
    ) { Text(glyph) }
}

/** One bar per member, filled to its position. Decorative: the summary text carries the meaning. */
@Composable
private fun MiniBars(positions: List<Int>) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (p in positions) {
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(OikosTheme.colors.cardBorder)) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(p / 100f).background(OikosTheme.colors.warning.copy(alpha = 0.85f)))
            }
        }
    }
}
