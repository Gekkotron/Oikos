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
import com.gekkotron.oikos.feature.security.presentation.ActionRow

/** Name, room and state, and the two settings an action has here (security actions spec §3.4). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActionSheet(row: ActionRow, onSet: (show: Boolean, ask: Boolean) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(row.name, style = OikosTheme.type.display.copy(fontSize = 22.sp))
            Text(
                listOfNotNull(row.areaName ?: stringResource(R.string.security_unassigned), actionStateWord(row)).joinToString(" · "),
                style = OikosTheme.type.body,
            )
            SheetSwitch(stringResource(R.string.security_show), true) { onSet(false, row.ask) }
            SheetSwitch(stringResource(R.string.security_action_ask), row.ask) { onSet(true, it) }
            Text(stringResource(R.string.security_hide_hint), style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
        }
    }
}

@Composable
private fun SheetSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = OikosDimens.minTouch)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
