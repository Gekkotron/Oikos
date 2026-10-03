package com.gekkotron.oikos.feature.security.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.security.R
import com.gekkotron.oikos.feature.security.presentation.SensorRow

/** Name, room and state, the one setting Security owns (whether the sensor is listed here, security spec §4.4), and a way to Home's customise sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SensorSheet(row: SensorRow, onHide: () -> Unit, onDismiss: () -> Unit, onCustomize: (() -> Unit)? = null) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(row.name, style = OikosTheme.type.display.copy(fontSize = 22.sp))
            Text(
                listOf(row.areaName ?: stringResource(R.string.security_unassigned), stateWord(row)).joinToString(" · "),
                style = OikosTheme.type.body,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = OikosDimens.minTouch)
                    .toggleable(value = true, role = Role.Switch, onValueChange = { if (!it) onHide() }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.security_show), Modifier.weight(1f))
                Switch(checked = true, onCheckedChange = null)
            }
            Text(stringResource(R.string.security_hide_hint), style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
            if (onCustomize != null) CustomizeLink(onCustomize)
        }
    }
}
