package com.gekkotron.oikos.feature.home.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.feature.home.presentation.CardUi
import com.gekkotron.oikos.feature.home.presentation.EffectNames
import com.gekkotron.oikos.feature.home.presentation.GroupCommands
import com.gekkotron.oikos.feature.home.presentation.GroupMath
import com.gekkotron.oikos.feature.home.presentation.hasColor
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Hs
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import kotlin.math.roundToInt

/** Room groups spec §4: group controls, then one row per member with inline per-member controls on tap. */
@Composable
internal fun GroupControls(
    card: CardUi,
    onSend: (String, Command) -> Unit,
    onSendGroup: (String, Command) -> Unit,
    onCustomizeMember: (String) -> Unit,
) {
    val room = card.areaName ?: stringResource(R.string.area_unassigned)
    val subtitle = if (card.kind == Kind.COVER) R.plurals.sheet_group_subtitle_covers else R.plurals.sheet_group_subtitle_lights
    Text(pluralStringResource(subtitle, card.members.size, room, card.members.size), style = OikosTheme.type.body)
    if (card.kind == Kind.COVER) CoverGroupControls(card, onSendGroup) else LightGroupControls(card, onSendGroup)
    Column {
        for (member in card.members) {
            HorizontalDivider()
            MemberRow(member, onSend, onCustomizeMember)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemberRow(
    member: CardUi,
    onSend: (String, Command) -> Unit,
    onCustomizeMember: (String) -> Unit,
) {
    val expandable = memberIsExpandable(member)
    var expanded by remember(member.id) { mutableStateOf(false) }
    val valueText = if (member.available) cardValueText(member.value) else stringResource(R.string.value_unavailable)
    val haptics = LocalHapticFeedback.current
    val customizeLabel = stringResource(R.string.action_customize)
    val expandLabel = stringResource(if (expanded) R.string.sheet_collapse else R.string.sheet_expand)
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = OikosDimens.minTouch)
                .combinedClickable(
                    enabled = member.available,
                    onClick = { if (expandable) expanded = !expanded },
                    onClickLabel = if (expandable) expandLabel else null,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onCustomizeMember(member.id)
                    },
                    onLongClickLabel = customizeLabel,
                )
                .testTag("member-${member.id}")
                .padding(vertical = 4.dp)
                .semantics { if (expandable) stateDescription = expandLabel },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(member.name, style = OikosTheme.type.body)
                Text(valueText, style = OikosTheme.type.cardName)
            }
            if (expandable) {
                Text(if (expanded) "▾" else "▸", modifier = Modifier.padding(end = 8.dp))
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
        if (expanded && expandable) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, bottom = 8.dp)
                    .testTag("member-expanded-${member.id}"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MemberExpandedControls(member, onSend)
            }
        }
    }
}

private fun memberIsExpandable(member: CardUi): Boolean {
    val caps = member.capabilities
    return when (member.kind) {
        Kind.LIGHT -> caps.brightness || caps.effects.isNotEmpty() || caps.hasColor
        Kind.COVER -> caps.position
        else -> false
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemberExpandedControls(member: CardUi, onSend: (String, Command) -> Unit) {
    val send: (Command) -> Unit = { onSend(member.id, it) }
    when (member.kind) {
        Kind.LIGHT -> {
            val light = member.state as? StateValue.Light
            if (member.capabilities.brightness) {
                var value by remember(light?.brightness) { mutableFloatStateOf((light?.brightness ?: 255).toFloat()) }
                Text(stringResource(R.string.sheet_brightness))
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = 1f..255f,
                    enabled = member.available,
                    onValueChangeFinished = { send(Command.TurnOn(brightness = value.roundToInt())) },
                    modifier = Modifier.testTag("member-brightness-${member.id}"),
                )
            }
            if (member.capabilities.effects.isNotEmpty()) {
                Text(stringResource(R.string.sheet_effect))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (effect in member.capabilities.effects) {
                        FilterChip(
                            selected = light?.effect == effect,
                            enabled = member.available,
                            onClick = { send(Command.TurnOn(effect = effect)) },
                            label = { Text(EffectNames.label(effect)) },
                        )
                    }
                }
            }
            if (member.capabilities.hasColor) {
                Text(stringResource(R.string.sheet_color))
                HueWheel(light?.color, onPick = { send(Command.TurnOn(color = it)) })
                FilterChip(
                    selected = (light?.color?.saturation ?: 1.0) < 0.02,
                    enabled = member.available,
                    onClick = { send(Command.TurnOn(color = Hs(0.0, 0.0))) },
                    label = { Text(stringResource(R.string.sheet_color_white)) },
                )
            }
        }
        Kind.COVER -> {
            if (member.capabilities.position) {
                val position = (member.state as? StateValue.Cover)?.position ?: 0
                var value by remember(position) { mutableFloatStateOf(position.toFloat()) }
                Text(stringResource(R.string.sheet_position))
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = 0f..100f,
                    steps = 19,
                    enabled = member.available,
                    onValueChangeFinished = { send(Command.SetPosition(value.roundToInt())) },
                    modifier = Modifier.testTag("member-position-${member.id}"),
                )
            }
        }
        else -> Unit
    }
}
