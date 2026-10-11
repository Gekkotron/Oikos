package com.gekkotron.oikos.feature.home.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.feature.home.presentation.AreaNames
import com.gekkotron.oikos.feature.home.presentation.CardUi
import com.gekkotron.oikos.feature.home.presentation.HomeUiBuilder
import com.gekkotron.oikos.feature.home.presentation.OverrideDraft
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.LightOnPreset
import com.gekkotron.oikos.model.PowerUnits
import com.gekkotron.oikos.model.Placement
import com.gekkotron.oikos.designsystem.R as DsR
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.UiOverride

/** Long-press override sheet (spec §5.2): rename, room, favourite, hide, position, card style, energy role. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomizeSheet(
    card: CardUi,
    rooms: List<Area>,
    onSave: (List<Area>, UiOverride) -> Unit,
    onDismiss: () -> Unit,
) {
    val start = OverrideDraft.from(card)
    var name by rememberSaveable(card.id) { mutableStateOf(start.name) }
    var areaId by rememberSaveable(card.id) { mutableStateOf(start.areaId) }
    var favorite by rememberSaveable(card.id) { mutableStateOf(start.favorite) }
    var hidden by rememberSaveable(card.id) { mutableStateOf(start.hidden) }
    var securityHidden by rememberSaveable(card.id) { mutableStateOf(start.securityHidden) }
    var homePinned by rememberSaveable(card.id) { mutableStateOf(start.homePinned) }
    var securityAction by rememberSaveable(card.id) { mutableStateOf(start.securityAction) }
    var securityAsk by rememberSaveable(card.id) { mutableStateOf(start.securityAsk) }
    var order by rememberSaveable(card.id) { mutableStateOf(start.order) }
    var cardStyle by rememberSaveable(card.id) { mutableStateOf(start.cardStyle) }
    var energyRole by rememberSaveable(card.id) { mutableStateOf(start.energyRole) }
    var energyInvert by rememberSaveable(card.id) { mutableStateOf(start.energyInvert) }
    var grouped by rememberSaveable(card.id) { mutableStateOf(start.grouped) }
    var asLight by rememberSaveable(card.id) { mutableStateOf(start.asLight) }
    var lightOnPreset by rememberSaveable(card.id) { mutableStateOf(start.lightOnPreset) }
    // Rooms typed here stay local to the draft; Save publishes them (spec §5.2), so dismissing without
    // saving never declares a room nobody asked to keep.
    var newRooms by remember(card.id) { mutableStateOf(emptyList<Area>()) }
    val draft = OverrideDraft(name, areaId, favorite, hidden, order, cardStyle, energyRole, energyInvert, securityHidden, homePinned, securityAction, securityAsk, grouped = grouped, asLight = asLight, lightOnPreset = lightOnPreset)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.customize_title), style = OikosTheme.type.display.copy(fontSize = 22.sp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.customize_name)) },
                supportingText = { Text(stringResource(R.string.customize_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.customize_room), style = OikosTheme.type.label)
            val allRooms = rooms + newRooms
            RoomPicker(
                allRooms, areaId,
                onSelect = { areaId = it },
                onCreate = { typed ->
                    val resolved = AreaNames.resolve(typed, allRooms) ?: return@RoomPicker null
                    if (resolved.isNew) newRooms = newRooms + resolved.area
                    resolved.area.id
                },
            )
            SwitchRow(stringResource(R.string.customize_favorite), favorite) { favorite = it }
            if (card.protocolKind == Kind.SWITCH) SwitchRow(stringResource(R.string.customize_as_light), asLight) { asLight = it }
            if (card.groupable) {
                SwitchRow(stringResource(if (card.kind == Kind.COVER) R.string.customize_grouped_cover else R.string.customize_grouped_light), grouped) { grouped = it }
            }
            // Energy or Security already shows it, so Home leaves it out unless pinned back.
            val onSecurity = Placement.onSecurity(card.kind, card.deviceClass, UiOverride(securityHidden = securityHidden, securityAction = securityAction))
            val elsewhere = energyRole != null || onSecurity
            if (elsewhere) {
                SwitchRow(stringResource(R.string.customize_show_on_home), homePinned && !hidden) { homePinned = it; if (it) hidden = false }
            } else {
                SwitchRow(stringResource(R.string.customize_hidden), hidden) { hidden = it }
            }
            if (Placement.securityKind(card.kind, card.deviceClass)) {
                SwitchRow(stringResource(R.string.customize_show_on_security), !securityHidden) { securityHidden = !it }
            }
            if (Placement.actionKind(card.kind)) {
                SwitchRow(stringResource(R.string.customize_show_on_security), securityAction) { securityAction = it }
                if (securityAction) SwitchRow(stringResource(R.string.customize_ask_before), securityAsk) { securityAsk = it }
            }
            OutlinedTextField(
                value = order,
                onValueChange = { order = it },
                label = { Text(stringResource(R.string.customize_order)) },
                supportingText = {
                    Text(stringResource(if (draft.orderValid) R.string.customize_order_hint else R.string.customize_order_error))
                },
                isError = !draft.orderValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            if (card.kind == Kind.LIGHT && card.capabilities.colorModes.isNotEmpty()) {
                Text(stringResource(R.string.customize_light_on_default), style = OikosTheme.type.label)
                ChipRow {
                    FilterChip(
                        selected = lightOnPreset == null,
                        onClick = { lightOnPreset = null },
                        label = { Text(stringResource(R.string.customize_light_on_resume)) },
                    )
                    LightOnPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = lightOnPreset == preset.key,
                            onClick = { lightOnPreset = preset.key },
                            label = { Text(stringResource(presetLabel(preset))) },
                        )
                    }
                }
            }
            if (card.kind == Kind.SENSOR) {
                Text(stringResource(R.string.customize_style), style = OikosTheme.type.label)
                ChipRow {
                    FilterChip(selected = cardStyle != HomeUiBuilder.HERO_WEATHER, onClick = { cardStyle = null }, label = { Text(stringResource(R.string.customize_style_standard)) })
                    FilterChip(selected = cardStyle == HomeUiBuilder.HERO_WEATHER, onClick = { cardStyle = HomeUiBuilder.HERO_WEATHER }, label = { Text(stringResource(R.string.customize_style_hero)) })
                }
                Text(stringResource(R.string.customize_energy), style = OikosTheme.type.label)
                ChipRow {
                    FilterChip(selected = energyRole == null, onClick = { energyRole = null }, label = { Text(stringResource(R.string.customize_energy_none)) })
                    EnergyRole.entries.forEach { role ->
                        FilterChip(selected = energyRole == role, onClick = { energyRole = role }, label = { Text(energyLabel(role)) })
                    }
                }
                val role = energyRole
                if (role != null && role.power && PowerUnits.factor(card.unit) == null) {
                    Text(stringResource(R.string.customize_energy_unit_hint), style = OikosTheme.type.label, color = OikosTheme.colors.warning)
                }
                if (role?.signed == true) {
                    SwitchRow(stringResource(R.string.customize_energy_invert), energyInvert) { energyInvert = it }
                }
            }
            Button(
                onClick = { onSave(newRooms, draft.applyTo(card)); onDismiss() },
                enabled = draft.orderValid,
                modifier = Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch),
            ) { Text(stringResource(R.string.customize_save)) }
            TextButton(onClick = { onSave(emptyList(), UiOverride()); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.customize_reset))
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch).toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = OikosTheme.type.body, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

private fun presetLabel(preset: LightOnPreset): Int = when (preset) {
    LightOnPreset.WHITE -> R.string.customize_light_on_white
    LightOnPreset.WARM -> R.string.customize_light_on_warm
    LightOnPreset.RED -> R.string.customize_light_on_red
    LightOnPreset.GREEN -> R.string.customize_light_on_green
    LightOnPreset.BLUE -> R.string.customize_light_on_blue
}

@Composable
private fun energyLabel(role: EnergyRole): String = stringResource(
    when (role) {
        EnergyRole.SOLAR -> DsR.string.energy_solar
        EnergyRole.GRID -> DsR.string.energy_grid
        EnergyRole.GRID_EXPORT -> DsR.string.energy_grid_export
        EnergyRole.BATTERY -> DsR.string.energy_battery
        EnergyRole.HOUSE -> DsR.string.energy_house
        EnergyRole.CONSUMER -> DsR.string.energy_consumer
        EnergyRole.WATER -> DsR.string.energy_water
    },
)
