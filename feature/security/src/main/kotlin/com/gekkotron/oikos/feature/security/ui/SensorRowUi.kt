package com.gekkotron.oikos.feature.security.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.components.OikosCard
import com.gekkotron.oikos.designsystem.icons.DoorIcon
import com.gekkotron.oikos.designsystem.icons.GarageIcon
import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.designsystem.icons.StaticIcon
import com.gekkotron.oikos.designsystem.icons.WindowIcon
import com.gekkotron.oikos.feature.security.R
import com.gekkotron.oikos.feature.security.presentation.GroupCount
import com.gekkotron.oikos.feature.security.presentation.SensorRow
import com.gekkotron.oikos.model.SensorGroup

@Composable
internal fun SensorRowCard(row: SensorRow, onClick: () -> Unit) {
    val accent = activeColor(row)
    val staleText = stringResource(R.string.security_stale)
    OikosCard(
        Modifier
            .fillMaxWidth()
            .testTag("sensor-${row.id}")
            .alpha(if (!row.available) 0.55f else if (row.stale) 0.6f else 1f)
            .then(if (row.stale) Modifier.semantics { stateDescription = staleText } else Modifier),
        accent = row.available && row.active == true,
        accentColor = if (row.group.danger) OikosTheme.colors.heat else null,
        onClick = onClick,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SensorIcon(row, accent)
            Column(Modifier.weight(1f)) {
                Text(row.name, style = OikosTheme.type.body)
                Text(row.areaName ?: stringResource(R.string.security_unassigned), style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
            }
            Text(stateWord(row), style = OikosTheme.type.body, color = accent ?: OikosTheme.colors.content)
        }
    }
}

/** Rose for danger, amber for intrusion; null when calm, unknown or unavailable (security spec §4.3). */
@Composable
private fun activeColor(row: SensorRow): Color? {
    if (!row.available || row.active != true) return null
    return if (row.group.danger) OikosTheme.colors.heat else OikosTheme.colors.warning
}

@Composable
private fun SensorIcon(row: SensorRow, accent: Color?) {
    val open = if (row.active == true) 1f else 0f
    val m = Modifier.size(28.dp)
    when (row.deviceClass) {
        "window" -> WindowIcon(open, accent, m)
        "garage_door" -> GarageIcon(open, accent, m)
        "door", "opening" -> DoorIcon(open, accent, m)
        else -> StaticIcon(glyph(row.group), accent, m)
    }
}

private fun glyph(group: SensorGroup): OikosGlyph = when (group) {
    SensorGroup.MOTION -> OikosGlyph.WALKER
    SensorGroup.SMOKE, SensorGroup.CO, SensorGroup.GAS -> OikosGlyph.FLAME
    SensorGroup.LEAK -> OikosGlyph.DROP
    SensorGroup.SOS -> OikosGlyph.SHIELD
    SensorGroup.OPENING, SensorGroup.VIBRATION, SensorGroup.TAMPER -> OikosGlyph.DOT
}

@Composable
internal fun stateWord(row: SensorRow): String {
    if (!row.available) return stringResource(R.string.security_unavailable)
    val active = row.active ?: return stringResource(R.string.security_unknown)
    return stringResource(
        when (row.group) {
            SensorGroup.OPENING -> if (active) R.string.security_open else R.string.security_closed
            SensorGroup.MOTION -> if (active) R.string.security_motion else R.string.security_clear
            SensorGroup.SMOKE, SensorGroup.CO, SensorGroup.GAS -> if (active) R.string.security_detected else R.string.security_clear
            SensorGroup.LEAK -> if (active) R.string.security_leak else R.string.security_dry
            SensorGroup.SOS -> if (active) R.string.security_sos else R.string.security_ok
            SensorGroup.VIBRATION -> if (active) R.string.security_vibration else R.string.security_still
            SensorGroup.TAMPER -> if (active) R.string.security_tampered else R.string.security_ok
        },
    )
}

/** "2 open · 1 motion", or "All clear". */
@Composable
internal fun summaryText(summary: List<GroupCount>): String {
    if (summary.isEmpty()) return stringResource(R.string.security_all_clear)
    return summary.map { (group, n) ->
        pluralStringResource(
            when (group) {
                SensorGroup.OPENING -> R.plurals.security_count_opening
                SensorGroup.MOTION -> R.plurals.security_count_motion
                SensorGroup.SMOKE -> R.plurals.security_count_smoke
                SensorGroup.CO -> R.plurals.security_count_co
                SensorGroup.GAS -> R.plurals.security_count_gas
                SensorGroup.LEAK -> R.plurals.security_count_leak
                SensorGroup.SOS -> R.plurals.security_count_sos
                SensorGroup.VIBRATION -> R.plurals.security_count_vibration
                SensorGroup.TAMPER -> R.plurals.security_count_tamper
            },
            n, n,
        )
    }.joinToString(" · ")
}
