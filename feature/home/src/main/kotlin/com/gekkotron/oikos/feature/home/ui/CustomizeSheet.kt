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
    var order by rememberSaveable(card.id) { mutableStateOf(start.order) }
    var cardStyle by rememberSaveable(card.id) { mutableStateOf(start.cardStyle) }
    var energyRole by rememberSaveable(card.id) { mutableStateOf(start.energyRole) }
    // Rooms typed here stay local to the draft; Save publishes them (spec §5.2), so dismissing without
    // saving never declares a room nobody asked to keep.
    var newRooms by remember(card.id) { mutableStateOf(emptyList<Area>()) }
    val draft = OverrideDraft(name, areaId, favorite, hidden, order, cardStyle, energyRole)

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
            SwitchRow(stringResource(R.string.customize_hidden), hidden) { hidden = it }
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

@Composable
private fun energyLabel(role: EnergyRole): String = stringResource(
    when (role) {
        EnergyRole.SOLAR -> DsR.string.energy_solar
        EnergyRole.GRID -> DsR.string.energy_grid
        EnergyRole.HOUSE -> DsR.string.energy_house
        EnergyRole.WATER_HEATER -> R.string.energy_water_heater
        EnergyRole.WATER -> DsR.string.energy_water
    },
)
