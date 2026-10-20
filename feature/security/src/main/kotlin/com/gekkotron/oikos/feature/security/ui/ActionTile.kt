package com.gekkotron.oikos.feature.security.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.components.OikosCard
import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.designsystem.icons.StaticIcon
import com.gekkotron.oikos.feature.security.R
import com.gekkotron.oikos.feature.security.presentation.ActionRow
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.SwitchType

/** A half-width action tile, laid out like [SensorTile]: glyph and name, then room and state (security actions spec §3.2). */
@Composable
internal fun ActionTile(row: ActionRow, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val on = row.available && row.on == true
    val c = OikosTheme.colors
    // Like Home's switch: gradient card, amber glyph in dark themes; OikosCard sets the text colour of an "on" card.
    val glyphTint = if (on && c.isDark) c.warning else null
    val staleText = stringResource(R.string.security_stale)
    OikosCard(
        modifier
            .testTag("action-${row.id}")
            .alpha(if (!row.available) 0.55f else if (row.stale) 0.6f else 1f)
            .then(if (row.stale) Modifier.semantics { stateDescription = staleText } else Modifier),
        on = on,
        enabled = row.available,
        onClick = onClick,
        onLongClick = onLongClick,
        onLongClickLabel = stringResource(R.string.security_action_settings),
        minHeight = 0.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StaticIcon(glyphFor(row), glyphTint, Modifier.size(22.dp))
            Text(row.name, style = OikosTheme.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                row.areaName ?: stringResource(R.string.security_unassigned),
                style = OikosTheme.type.label,
                color = if (on) Color.Unspecified else c.contentMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actionStateWord(row)?.let { Text(it, style = OikosTheme.type.body, maxLines = 1) }
        }
    }
}

/** Device sheet spec §5.3: a Security Actions tile wears the switch's type glyph. */
private fun glyphFor(row: ActionRow): OikosGlyph = when {
    row.kind == Kind.BUTTON -> OikosGlyph.PLAY
    row.switchType == SwitchType.CHIME -> OikosGlyph.PLAY
    row.switchType == SwitchType.SIREN -> OikosGlyph.BOLT
    // Door opener keeps the plug tile until a dedicated glyph lands; the sheet's wording speaks the type.
    else -> OikosGlyph.PLUG
}

/** "On" / "Off" / "Unavailable"; null for an available button (it has no state). */
@Composable
internal fun actionStateWord(row: ActionRow): String? = when {
    !row.available -> stringResource(R.string.security_unavailable)
    row.kind == Kind.BUTTON -> null
    row.on == true -> stringResource(R.string.security_action_on)
    row.on == false -> stringResource(R.string.security_action_off)
    else -> stringResource(R.string.security_unknown)
}
