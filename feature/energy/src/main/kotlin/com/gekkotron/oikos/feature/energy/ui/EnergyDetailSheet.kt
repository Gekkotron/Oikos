package com.gekkotron.oikos.feature.energy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.designsystem.icons.StaticIcon
import com.gekkotron.oikos.feature.energy.R
import com.gekkotron.oikos.feature.energy.presentation.EnergyFormat
import com.gekkotron.oikos.feature.energy.presentation.EnergyNode
import com.gekkotron.oikos.feature.energy.presentation.EnergyUiState
import com.gekkotron.oikos.feature.energy.presentation.MeterOption
import com.gekkotron.oikos.feature.energy.presentation.NodeKind
import com.gekkotron.oikos.feature.energy.presentation.NodeStatus
import com.gekkotron.oikos.feature.energy.presentation.SensorEdit
import com.gekkotron.oikos.feature.energy.presentation.SensorUi
import com.gekkotron.oikos.model.ConsumerIcon
import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.PowerUnits
import java.util.Locale
import com.gekkotron.oikos.designsystem.R as DsR

/** A node's details: its value, the sensors behind it, and an editor per sensor (name, role, invert, icon, energy meter). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EnergyDetailSheet(
    node: EnergyNode,
    state: EnergyUiState,
    locale: Locale,
    onSave: (String, SensorEdit) -> Unit,
    onDismiss: () -> Unit,
) {
    val sensors = node.sensorIds.mapNotNull(state.sensors::get)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(nodeLabel(node), style = OikosTheme.type.display.copy(fontSize = 22.sp))
            Text(nodeValueText(node, locale), style = OikosTheme.type.display.copy(fontSize = 28.sp), modifier = Modifier.alpha(statusAlpha(node.status)))
            Text(statusText(node.status), style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
            if (node.kind == NodeKind.HOUSE && sensors.isEmpty()) {
                val inputs = state.diagram.filter { it.kind == NodeKind.SOLAR || it.kind == NodeKind.GRID || it.kind == NodeKind.BATTERY }
                if (inputs.isNotEmpty()) {
                    val joined = inputs.map { nodeLabel(it) }.joinToString(" + ")
                    Text(stringResource(R.string.energy_details_calculated, joined), style = OikosTheme.type.body)
                }
            }
            if (sensors.isNotEmpty()) {
                Text(stringResource(R.string.energy_details_sensors), style = OikosTheme.type.label)
                sensors.forEach { sensor ->
                    SensorRow(sensor, state.meters, locale, startOpen = sensors.size == 1, onSave = { onSave(sensor.id, it); onDismiss() })
                }
            }
        }
    }
}

@Composable
private fun SensorRow(sensor: SensorUi, meters: List<MeterOption>, locale: Locale, startOpen: Boolean, onSave: (SensorEdit) -> Unit) {
    var open by rememberSaveable(sensor.id) { mutableStateOf(startOpen) }
    HorizontalDivider()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = OikosDimens.minTouch)
            .alpha(statusAlpha(sensor.status))
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.energy_details_edit)) { open = !open }
            .testTag("energy-sensor-${sensor.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(sensor.name, style = OikosTheme.type.body, modifier = Modifier.weight(1f))
        Text(sensorValueText(sensor, locale), style = OikosTheme.type.body)
    }
    if (open) SensorEditor(sensor, meters, onSave)
}

@Composable
private fun SensorEditor(sensor: SensorUi, meters: List<MeterOption>, onSave: (SensorEdit) -> Unit) {
    var name by rememberSaveable(sensor.id) { mutableStateOf(sensor.name) }
    var role by rememberSaveable(sensor.id) { mutableStateOf<EnergyRole?>(sensor.role) }
    var invert by rememberSaveable(sensor.id) { mutableStateOf(sensor.invert) }
    var icon by rememberSaveable(sensor.id) { mutableStateOf(sensor.icon) }
    var meter by rememberSaveable(sensor.id) { mutableStateOf(sensor.meterId) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.energy_details_name)) },
            supportingText = { Text(stringResource(R.string.energy_details_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.energy_details_role), style = OikosTheme.type.label)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = role == null, onClick = { role = null }, label = { Text(stringResource(R.string.energy_details_role_none)) })
            EnergyRole.entries.forEach { r ->
                FilterChip(selected = role == r, onClick = { role = r }, label = { Text(roleLabel(r)) })
            }
        }
        val current = role
        if (current != null && current.power && PowerUnits.factor(sensor.unit) == null) {
            Text(stringResource(R.string.energy_details_unit_hint), style = OikosTheme.type.label, color = OikosTheme.colors.warning)
        }
        if (current?.signed == true) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch).toggleable(invert, role = Role.Switch, onValueChange = { invert = it }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.energy_details_invert), style = OikosTheme.type.body, modifier = Modifier.weight(1f))
                Switch(checked = invert, onCheckedChange = null)
            }
        }
        if (current == EnergyRole.CONSUMER) {
            Text(stringResource(R.string.energy_details_icon), style = OikosTheme.type.label)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconChip(OikosGlyph.PLUG, stringResource(R.string.energy_icon_default), icon == null, "energy-icon-default") { icon = null }
                ConsumerIcon.entries.forEach { i ->
                    IconChip(i.glyph, iconLabel(i), icon == i, "energy-icon-${i.key}") { icon = i }
                }
            }
        }
        if (current?.power == true) {
            Text(stringResource(R.string.energy_details_meter), style = OikosTheme.type.label)
            val deviceId = sensor.deviceId
            val ordered = meters.filter { it.deviceId != null && it.deviceId == deviceId } + meters.filterNot { it.deviceId != null && it.deviceId == deviceId }
            val autoName = meters.firstOrNull { it.id == sensor.autoMeterId }?.name
            val auto = stringResource(R.string.energy_meter_auto)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = meter == null,
                    onClick = { meter = null },
                    label = { Text(if (autoName != null) "$auto · $autoName" else auto) },
                    modifier = Modifier.heightIn(min = OikosDimens.minTouch).testTag("energy-meter-auto"),
                )
                ordered.forEach { m ->
                    FilterChip(
                        selected = meter == m.id,
                        onClick = { meter = m.id },
                        label = { Text(m.name) },
                        modifier = Modifier.heightIn(min = OikosDimens.minTouch).testTag("energy-meter-${m.id}"),
                    )
                }
            }
        }
        Button(
            onClick = { onSave(SensorEdit(name, role, invert, icon, meter)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch),
        ) { Text(stringResource(R.string.energy_details_save)) }
    }
}

@Composable
private fun IconChip(glyph: OikosGlyph, label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { StaticIcon(glyph, modifier = Modifier.size(22.dp)) },
        modifier = Modifier.heightIn(min = OikosDimens.minTouch).testTag(tag).semantics { contentDescription = label },
    )
}

@Composable
private fun iconLabel(icon: ConsumerIcon): String = stringResource(
    when (icon) {
        ConsumerIcon.WATER_HEATER -> R.string.energy_icon_water_heater
        ConsumerIcon.HEAT_PUMP -> R.string.energy_icon_heat_pump
        ConsumerIcon.OVEN -> R.string.energy_icon_oven
        ConsumerIcon.FRIDGE -> R.string.energy_icon_fridge
        ConsumerIcon.WASHER -> R.string.energy_icon_washer
        ConsumerIcon.DRYER -> R.string.energy_icon_dryer
        ConsumerIcon.DISHWASHER -> R.string.energy_icon_dishwasher
    },
)

/** The sensor's own reading, in its own unit ("0.5 kW", "312 L"), not the node's converted watts. */
@Composable
private fun sensorValueText(sensor: SensorUi, locale: Locale): String = when {
    sensor.status == NodeStatus.UNAVAILABLE -> stringResource(R.string.energy_unavailable)
    sensor.value == null -> stringResource(R.string.energy_unknown)
    else -> EnergyFormat.amount(sensor.value, sensor.unit, locale)
}

@Composable
private fun statusText(status: NodeStatus): String = stringResource(
    when (status) {
        NodeStatus.LIVE -> R.string.energy_live
        NodeStatus.STALE -> R.string.energy_stale
        NodeStatus.UNAVAILABLE -> R.string.energy_unavailable
    },
)

@Composable
private fun roleLabel(role: EnergyRole): String = stringResource(
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
