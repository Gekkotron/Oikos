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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.feature.home.presentation.CardFormatter
import com.gekkotron.oikos.feature.home.presentation.CardUi
import com.gekkotron.oikos.feature.home.presentation.EffectNames
import com.gekkotron.oikos.feature.home.presentation.StepMath
import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ControlSheet(card: CardUi, onSend: (String, Command) -> Unit, onCustomize: () -> Unit, onDismiss: () -> Unit) {
    val send: (Command) -> Unit = { onSend(card.id, it) }
    // Fully expanded and scrollable, so every control stays reachable at 200 % font or in landscape (spec §5.3).
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(card.name, style = OikosTheme.type.display.copy(fontSize = 22.sp))
            Text(cardValueText(card.value), style = OikosTheme.type.body)
            when (card.kind) {
                Kind.LIGHT -> LightControls(card, onSend)
                Kind.COVER -> CoverControls(card, send)
                Kind.CLIMATE -> ClimateControls(card, send)
                Kind.NUMBER -> NumberControls(card, send)
                Kind.SELECT -> SelectControls(card, send)
                Kind.ALARM_PANEL -> AlarmControls(card, send)
                else -> Unit
            }
            TextButton(onClick = onCustomize, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.sheet_customize)) }
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
    val target = climate?.target ?: climate?.current ?: ((min + max) / 2)
    val locale = LocalConfiguration.current.locales[0]
    val decrease = stringResource(R.string.sheet_decrease)
    val increase = stringResource(R.string.sheet_increase)
    Text(stringResource(R.string.sheet_target))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
        FilledTonalIconButton(
            onClick = { onSend(Command.SetTemperature(StepMath.stepTemperature(target, -step, min, max))) },
            enabled = target > min,
            modifier = Modifier.semantics { contentDescription = decrease },
        ) { Text("−", fontSize = 22.sp) }
        Text(CardFormatter.number(target, locale) + "°", style = OikosTheme.type.display)
        FilledTonalIconButton(
            onClick = { onSend(Command.SetTemperature(StepMath.stepTemperature(target, step, min, max))) },
            enabled = target < max,
            modifier = Modifier.semantics { contentDescription = increase },
        ) { Text("+", fontSize = 22.sp) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (mode in caps.hvacModes) {
            FilterChip(selected = climate?.mode == mode, onClick = { onSend(Command.SetHvacMode(mode)) }, label = { Text(modeLabel(mode)) })
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

@Composable
private fun AlarmControls(card: CardUi, onSend: (Command) -> Unit) {
    val caps = card.capabilities
    var code by rememberSaveable { mutableStateOf("") }
    if (caps.codeArmRequired) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text(stringResource(R.string.sheet_code)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    val codeArg = code.ifEmpty { null }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (mode in AlarmMode.entries.filter { it in caps.alarmModes }) {
            FilledTonalButton(onClick = { onSend(Command.Arm(mode, codeArg)) }) { Text(stringResource(armLabel(mode))) }
        }
    }
    Button(onClick = { onSend(Command.Disarm(codeArg)) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.sheet_disarm)) }
}
