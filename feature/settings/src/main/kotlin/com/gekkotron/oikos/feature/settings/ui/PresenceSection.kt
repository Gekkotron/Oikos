package com.gekkotron.oikos.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.data.PresenceConfig
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.settings.R
import com.gekkotron.oikos.feature.settings.presentation.PresenceSettingsUi

@Composable
fun PresenceSection(
    ui: PresenceSettingsUi,
    permissionStatus: PresencePermissionStatus,
    onToggle: (Boolean) -> Unit,
    onAliasChange: (String) -> Unit,
    onUseCurrentLocation: () -> Unit,
    onRadiusChange: (Int) -> Unit,
    onRequestPermission: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.presence_section_title),
            style = OikosTheme.type.label,
            color = OikosTheme.colors.contentMuted,
            modifier = Modifier.semantics { heading() },
        )
        val toggleEnabled = permissionStatus == PresencePermissionStatus.Granted
        Row(
            Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch)
                .toggleable(value = ui.enabled && toggleEnabled, enabled = toggleEnabled, role = Role.Switch, onValueChange = onToggle)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.presence_toggle_label), style = OikosTheme.type.body, modifier = Modifier.weight(1f))
            Switch(checked = ui.enabled && toggleEnabled, onCheckedChange = null, enabled = toggleEnabled)
        }
        Text(stringResource(R.string.presence_toggle_summary), style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
        when (permissionStatus) {
            PresencePermissionStatus.FineMissing -> PermissionRow(stringResource(R.string.presence_permission_fine), onRequestPermission)
            PresencePermissionStatus.BackgroundMissing -> PermissionRow(stringResource(R.string.presence_permission_background), onRequestPermission)
            PresencePermissionStatus.Denied -> Text(stringResource(R.string.presence_permission_denied), style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
            PresencePermissionStatus.Granted -> {}
        }
        OutlinedTextField(
            value = ui.alias,
            onValueChange = onAliasChange,
            label = { Text(stringResource(R.string.presence_alias_label)) },
            supportingText = { Text(stringResource(R.string.presence_alias_hint), style = OikosTheme.type.label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val home = ui.home
        if (home != null) {
            Text(stringResource(R.string.presence_home_set, home.first, home.second), style = OikosTheme.type.body)
        } else {
            Text(stringResource(R.string.presence_home_unset), style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
        }
        OutlinedButton(onClick = onUseCurrentLocation, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.presence_use_current_location))
        }
        Text(stringResource(R.string.presence_radius_label, ui.radiusM), style = OikosTheme.type.body)
        Slider(
            value = ui.radiusM.toFloat(),
            onValueChange = { onRadiusChange(it.toInt()) },
            valueRange = PresenceConfig.MIN_RADIUS_M.toFloat()..PresenceConfig.MAX_RADIUS_M.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PermissionRow(message: String, onGrant: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(message, style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
        OutlinedButton(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.presence_grant_permission))
        }
    }
}

enum class PresencePermissionStatus { Granted, FineMissing, BackgroundMissing, Denied }
