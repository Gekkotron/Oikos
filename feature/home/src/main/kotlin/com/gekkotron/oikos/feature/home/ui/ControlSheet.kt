package com.gekkotron.oikos.feature.home.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.alarm.AlarmControls
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.feature.home.presentation.CardFormatter
import com.gekkotron.oikos.feature.home.presentation.CardUi
import com.gekkotron.oikos.feature.home.presentation.CardValue
import com.gekkotron.oikos.feature.home.presentation.EffectNames
import com.gekkotron.oikos.feature.home.presentation.hasColor
import com.gekkotron.oikos.feature.home.presentation.StepMath
import com.gekkotron.oikos.model.ClimatePresets
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Hs
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Quiet time after the last +/- tap before the setpoint goes out: a burst of taps sends one command. */
internal const val SETPOINT_DEBOUNCE_MS = 600L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ControlSheet(
    card: CardUi,
    onSend: (String, Command) -> Unit,
    onCustomize: () -> Unit,
    onDismiss: () -> Unit,
    onOpenHistory: (() -> Unit)? = null,
    onSendGroup: (String, Command) -> Unit = { _, _ -> },
    onCustomizeMember: (String) -> Unit = {},
) {
    val send: (Command) -> Unit = { onSend(card.id, it) }
    // Fully expanded and scrollable, so every control stays reachable at 200 % font or in landscape (spec §5.3).
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (card.isGroup) {
                Text(groupName(card), style = OikosTheme.type.display.copy(fontSize = 22.sp))
                GroupControls(card, onSend, onSendGroup, onCustomizeMember)
            } else {
                Text(card.name, style = OikosTheme.type.display.copy(fontSize = 22.sp))
                // The card leads with the setpoint, which the climate controls below already show: the room temperature is more useful here.
                val header = (card.value as? CardValue.Climate)?.takeIf { card.kind == Kind.CLIMATE }?.copy(target = null) ?: card.value
                Text(cardValueText(header, card.kind), style = OikosTheme.type.body)
                when (card.kind) {
                    Kind.LIGHT -> LightControls(card, onSend)
                    Kind.COVER -> CoverControls(card, send)
                    Kind.CLIMATE -> ClimateControls(card, send)
                    Kind.NUMBER -> NumberControls(card, send)
                    Kind.SELECT -> SelectControls(card, send)
                    Kind.ALARM_PANEL -> AlarmControls((card.state as? StateValue.Alarm)?.state, card.available, card.capabilities, send)
                    Kind.VACUUM -> VacuumControls(card, send)
                    Kind.WATER_HEATER -> WaterHeaterControls(card, send) { id, cmd -> onSend(id, cmd) }
                    else -> Unit
                }
            }
            if (card.hasHistory && onOpenHistory != null) {
                // History first: on Home, dismissing a member's sheet reopens its group, which history must not sit under.
                TextButton(onClick = { onOpenHistory(); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.sheet_history)) }
            }
            TextButton(onClick = onCustomize, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (card.isGroup) R.string.sheet_customize_group else R.string.sheet_customize))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LightControls(card: CardUi, onSend: (String, Command) -> Unit) {
    val light = card.state as? StateValue.Light
    val send: (Command) -> Unit = { onSend(card.id, it) }
    Row(Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.sheet_power), Modifier.weight(1f))
        Switch(checked = light?.on == true, onCheckedChange = { send(if (it) Command.TurnOn() else Command.TurnOff) })
    }
    if (card.capabilities.brightness) {
        var value by remember(light?.brightness) { mutableFloatStateOf((light?.brightness ?: 255).toFloat()) }
        Text(stringResource(R.string.sheet_brightness))
        Slider(
            value = value,
            onValueChange = { value = it },
            valueRange = 1f..255f,
            onValueChangeFinished = { send(Command.TurnOn(brightness = value.roundToInt())) },
        )
    }
    if (card.capabilities.effects.isNotEmpty()) {
        Text(stringResource(R.string.sheet_effect))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (effect in card.capabilities.effects) {
                FilterChip(
                    selected = light?.effect == effect,
                    onClick = { send(Command.TurnOn(effect = effect)) },
                    label = { Text(EffectNames.label(effect)) },
                )
            }
        }
    }
    for (companion in card.companions) {
        Text(companion.name)
        when (companion.kind) {
            Kind.NUMBER -> NumberControls(companion) { onSend(companion.id, it) }
            Kind.SELECT -> SelectControls(companion) { onSend(companion.id, it) }
            else -> Unit
        }
    }
    if (card.capabilities.hasColor) {
        Text(stringResource(R.string.sheet_color))
        HueWheel(light?.color, onPick = { send(Command.TurnOn(color = it)) })
        FilterChip(
            selected = (light?.color?.saturation ?: 1.0) < 0.02,
            onClick = { send(Command.TurnOn(color = Hs(0.0, 0.0))) },
            label = { Text(stringResource(R.string.sheet_color_white)) },
        )
    }
}

@Composable
private fun CoverControls(card: CardUi, onSend: (Command) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(onClick = { onSend(Command.Open) }, Modifier.weight(1f)) { Text(stringResource(R.string.sheet_open)) }
        FilledTonalButton(onClick = { onSend(Command.Stop) }, Modifier.weight(1f)) { Text(stringResource(R.string.sheet_stop)) }
        FilledTonalButton(onClick = { onSend(Command.Close) }, Modifier.weight(1f)) { Text(stringResource(R.string.sheet_close)) }
    }
    if (card.capabilities.position) {
        val position = (card.state as? StateValue.Cover)?.position ?: 0
        var value by remember(position) { mutableFloatStateOf(position.toFloat()) }
        Text(stringResource(R.string.sheet_position))
        Slider(
            value = value,
            onValueChange = { value = it },
            valueRange = 0f..100f,
            steps = 19,
            onValueChangeFinished = { onSend(Command.SetPosition(value.roundToInt())) },
        )
    }
}

@Composable
private fun ClimateControls(card: CardUi, onSend: (Command) -> Unit) {
    val climate = card.state as? StateValue.Climate
    val caps = card.capabilities
    val min = caps.minTemp ?: 7.0
    val max = caps.maxTemp ?: 35.0
    val step = caps.tempStep ?: 1.0
    val confirmed = climate?.target ?: climate?.current ?: ((min + max) / 2)
    // +/- move a local draft at once and send it after SETPOINT_DEBOUNCE_MS of quiet: steps no longer wait for the
    // device's echo, and a burst of taps sends one command. A draft still pending when the sheet closes goes out then.
    val draft = remember(card.id) { mutableStateOf<Double?>(null) }
    val target = draft.value ?: confirmed
    val send by rememberUpdatedState(onSend)
    LaunchedEffect(draft.value) {
        val value = draft.value ?: return@LaunchedEffect
        delay(SETPOINT_DEBOUNCE_MS)
        draft.value = null
        send(Command.SetTemperature(value))
    }
    DisposableEffect(card.id) {
        onDispose { draft.value?.let { draft.value = null; send(Command.SetTemperature(it)) } }
    }
    val locale = LocalConfiguration.current.locales[0]
    val decrease = stringResource(R.string.sheet_decrease)
    val increase = stringResource(R.string.sheet_increase)
    val powerable = "off" in caps.hvacModes && caps.hvacModes.any { it != "off" }
    if (powerable) {
        val isOn = climate?.mode != null && climate.mode != "off"
        val powerLabel = stringResource(R.string.sheet_power)
        Row(
            Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(powerLabel, modifier = Modifier.weight(1f))
            Switch(
                checked = isOn,
                onCheckedChange = { on ->
                    val next = if (on) caps.hvacModes.firstOrNull { it == "auto" } ?: caps.hvacModes.first { it != "off" } else "off"
                    onSend(Command.SetHvacMode(next))
                },
                modifier = Modifier.semantics { contentDescription = powerLabel },
            )
        }
    }
    Text(stringResource(R.string.sheet_target))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
        FilledTonalIconButton(
            onClick = { draft.value = StepMath.stepTemperature(draft.value ?: confirmed, -step, min, max) },
            enabled = target > min,
            modifier = Modifier.semantics { contentDescription = decrease },
        ) { Text("−", fontSize = 22.sp) }
        Text(CardFormatter.number(target, locale) + "°", style = OikosTheme.type.display)
        FilledTonalIconButton(
            onClick = { draft.value = StepMath.stepTemperature(draft.value ?: confirmed, step, min, max) },
            enabled = target < max,
            modifier = Modifier.semantics { contentDescription = increase },
        ) { Text("+", fontSize = 22.sp) }
    }
    val presets = ClimatePresets.parseOrDefault(card.ui.climatePresets).filter { it in min..max }
    if (presets.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in presets) {
                FilterChip(
                    selected = target == preset,
                    onClick = { draft.value = null; onSend(Command.SetTemperature(preset)) },
                    label = { Text(CardFormatter.number(preset, locale) + "°") },
                )
            }
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (mode in caps.hvacModes) {
            FilterChip(selected = climate?.mode == mode, onClick = { onSend(Command.SetHvacMode(mode)) }, label = { Text(modeLabel(mode)) })
        }
    }
    if (caps.fanModes.isNotEmpty()) {
        Text(stringResource(R.string.sheet_fan_speed))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (fan in caps.fanModes) {
                FilterChip(selected = climate?.fanMode == fan, onClick = { onSend(Command.SetFanMode(fan)) }, label = { Text(fanModeLabel(fan)) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WaterHeaterControls(card: CardUi, onSend: (Command) -> Unit, onSendCompanion: (String, Command) -> Unit) {
    val climate = card.state as? StateValue.Climate
    val caps = card.capabilities
    val min = caps.minTemp ?: 43.0
    val max = caps.maxTemp ?: 60.0
    val step = caps.tempStep ?: 1.0
    val target = climate?.target ?: ((min + max) / 2)
    val locale = LocalConfiguration.current.locales[0]
    val decrease = stringResource(R.string.sheet_decrease)
    val increase = stringResource(R.string.sheet_increase)

    // Mode chips: every discovered mode except "off"; "eco" and "performance" get the Éco / Force labels.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (mode in caps.hvacModes.filter { it != "off" }) {
            FilterChip(
                selected = climate?.mode == mode,
                onClick = { onSend(Command.SetHvacMode(mode)) },
                label = { Text(waterHeaterModeLabel(mode)) },
                enabled = card.available,
            )
        }
    }

    Text(stringResource(R.string.sheet_target))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
        FilledTonalIconButton(
            onClick = { onSend(Command.SetTemperature(StepMath.stepTemperature(target, -step, min, max))) },
            enabled = card.available && target > min,
            modifier = Modifier.semantics { contentDescription = decrease },
        ) { Text("−", fontSize = 22.sp) }
        Text(CardFormatter.number(target, locale) + "°", style = OikosTheme.type.display)
        FilledTonalIconButton(
            onClick = { onSend(Command.SetTemperature(StepMath.stepTemperature(target, step, min, max))) },
            enabled = card.available && target < max,
            modifier = Modifier.semantics { contentDescription = increase },
        ) { Text("+", fontSize = 22.sp) }
    }

    // Same-device Kind.NUMBER companions (e.g. Force power); each still has its own standalone card.
    for (companion in card.companions) {
        if (companion.kind == Kind.NUMBER) {
            Text(companion.name)
            NumberControls(companion) { onSendCompanion(companion.id, it) }
        }
    }
}

@Composable
private fun NumberControls(card: CardUi, onSend: (Command) -> Unit) {
    val caps = card.capabilities
    val min = caps.min ?: 0.0
    val max = caps.max ?: 100.0
    val step = caps.step ?: 1.0
    val current = (card.state as? StateValue.Numeric)?.value ?: min
    var value by remember(current) { mutableFloatStateOf(current.toFloat()) }
    val steps = ((max - min) / step).roundToInt() - 1
    val locale = LocalConfiguration.current.locales[0]
    Text(
        listOfNotNull(CardFormatter.number(StepMath.snap(value.toDouble(), min, step), locale), card.unit).joinToString(" "),
        style = OikosTheme.type.display,
    )
    Slider(
        value = value,
        onValueChange = { value = it },
        valueRange = min.toFloat()..max.toFloat(),
        steps = if (steps in 0..200) steps else 0,
        onValueChangeFinished = { onSend(Command.SetNumber(StepMath.snap(value.toDouble(), min, step))) },
    )
}

@Composable
private fun SelectControls(card: CardUi, onSend: (Command) -> Unit) {
    val current = (card.state as? StateValue.Text)?.value
    for (option in card.capabilities.options) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = OikosDimens.minTouch)
                .selectable(selected = option == current, role = Role.RadioButton) { onSend(Command.SelectOption(option)) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = option == current, onClick = null)
            Text(option, Modifier.padding(start = 12.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VacuumControls(card: CardUi, onSend: (Command) -> Unit) {
    val vacuum = card.state as? StateValue.Vacuum
    val actions = card.capabilities.vacuumActions
    // Start turns into Pause while cleaning, when the vacuum can pause
    val primary = if (vacuum?.state == "cleaning" && "pause" in actions) "pause" else "start"
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (primary in actions) {
            Button(onClick = { onSend(Command.VacuumAction(primary)) }, enabled = card.available) { Text(stringResource(vacuumActionLabel(primary))) }
        }
        for (action in listOf("stop", "return_to_base", "locate")) {
            if (action in actions) {
                FilledTonalButton(onClick = { onSend(Command.VacuumAction(action)) }, enabled = card.available) {
                    Text(stringResource(vacuumActionLabel(action)))
                }
            }
        }
    }
    if (card.capabilities.fanSpeeds.isNotEmpty()) {
        Text(stringResource(R.string.sheet_suction))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (speed in card.capabilities.fanSpeeds) {
                FilterChip(
                    selected = vacuum?.fanSpeed == speed,
                    onClick = { onSend(Command.SetFanSpeed(speed)) },
                    enabled = card.available,
                    label = { Text(speed) },
                )
            }
        }
    }
}

