package com.gekkotron.oikos.feature.home.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.feature.home.presentation.CardUi
import com.gekkotron.oikos.feature.home.presentation.GroupCommands
import com.gekkotron.oikos.feature.home.presentation.GroupMath
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Kind
import kotlin.math.roundToInt

/** Room groups spec §4: group controls, then one row per member. */
@Composable
internal fun GroupControls(card: CardUi, onSend: (String, Command) -> Unit, onSendGroup: (String, Command) -> Unit, onOpenMember: (String) -> Unit) {
    val room = card.areaName ?: stringResource(R.string.area_unassigned)
    val subtitle = if (card.kind == Kind.COVER) R.plurals.sheet_group_subtitle_covers else R.plurals.sheet_group_subtitle_lights
    Text(pluralStringResource(subtitle, card.members.size, room, card.members.size), style = OikosTheme.type.body)
    if (card.kind == Kind.COVER) CoverGroupControls(card, onSendGroup) else LightGroupControls(card, onSendGroup)
    Column {
        for (member in card.members) {
            HorizontalDivider()
            MemberRow(member, onSend, onOpenMember)
        }
    }
}

@Composable
private fun CoverGroupControls(card: CardUi, onSendGroup: (String, Command) -> Unit) {
    val send: (Command) -> Unit = { onSendGroup(card.id, it) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(onClick = { send(Command.Open) }, Modifier.weight(1f)) { Text(stringResource(R.string.sheet_open)) }
        FilledTonalButton(onClick = { send(Command.Stop) }, Modifier.weight(1f)) { Text(stringResource(R.string.sheet_stop)) }
        FilledTonalButton(onClick = { send(Command.Close) }, Modifier.weight(1f)) { Text(stringResource(R.string.sheet_close)) }
    }
    if (card.capabilities.position) {
        val start = GroupMath.meanPosition(card)
        var value by remember(start) { mutableFloatStateOf(start.toFloat()) }
        Row { Text(stringResource(R.string.sheet_all_to), Modifier.weight(1f)); Text(stringResource(R.string.value_percent, value.roundToInt())) }
        Slider(
            value = value,
            onValueChange = { value = it },
            valueRange = 0f..100f,
            steps = 19,
            onValueChangeFinished = { send(Command.SetPosition(value.roundToInt())) },
            modifier = Modifier.testTag("group-slider-${card.id}"),
        )
    }
}

@Composable
private fun LightGroupControls(card: CardUi, onSendGroup: (String, Command) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.sheet_power), Modifier.weight(1f))
        val power = stringResource(R.string.sheet_power)
        Switch(
            checked = card.on,
            onCheckedChange = { onSendGroup(card.id, GroupCommands.toggle(card)) },
            modifier = Modifier.testTag("group-power-${card.id}").semantics { contentDescription = power },
        )
    }
    if (card.capabilities.brightness) {
        val start = GroupMath.meanBrightness(card)
        var value by remember(start) { mutableFloatStateOf(start.toFloat()) }
        Text(stringResource(R.string.sheet_brightness))
        Slider(
            value = value,
            onValueChange = { value = it },
            valueRange = 1f..255f,
            onValueChangeFinished = { onSendGroup(card.id, Command.TurnOn(brightness = value.roundToInt())) },
            modifier = Modifier.testTag("group-slider-${card.id}"),
        )
    }
}

@Composable
private fun MemberRow(member: CardUi, onSend: (String, Command) -> Unit, onOpenMember: (String) -> Unit) {
    val valueText = if (member.available) cardValueText(member.value) else stringResource(R.string.value_unavailable)
    Row(
        Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch).clickable { onOpenMember(member.id) }.testTag("member-${member.id}").padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(member.name, style = OikosTheme.type.body)
            Text(valueText, style = OikosTheme.type.cardName)
        }
        if (member.kind == Kind.COVER) {
            for ((glyph, command, label) in listOf(
                Triple("▲", Command.Open, R.string.sheet_open),
                Triple("■", Command.Stop, R.string.sheet_stop),
                Triple("▼", Command.Close, R.string.sheet_close),
            )) {
                val text = stringResource(label)
                val tag = when (command) { Command.Open -> "open"; Command.Stop -> "stop"; else -> "close" }
                FilledTonalIconButton(
                    onClick = { onSend(member.id, command) },
                    enabled = member.available,
                    modifier = Modifier.size(OikosDimens.minTouch).testTag("member-$tag-${member.id}").semantics { contentDescription = "$text ${member.name}" },
                ) { Text(glyph) }
            }
        } else {
            Switch(
                checked = member.on,
                enabled = member.available,
                onCheckedChange = { onSend(member.id, if (it) Command.TurnOn() else Command.TurnOff) },
                // Its own focus stop beside the row: TalkBack must say which light it switches.
                modifier = Modifier.semantics { contentDescription = member.name },
            )
        }
    }
}
