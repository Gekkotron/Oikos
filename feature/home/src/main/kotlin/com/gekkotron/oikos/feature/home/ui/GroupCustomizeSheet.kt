package com.gekkotron.oikos.feature.home.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.feature.home.presentation.CardUi

/** Room groups spec §6: name, favorite, members, Ungroup all. [onSave] gets the typed name, the favorite flag and the unticked member ids. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupCustomizeSheet(card: CardUi, onSave: (String, Boolean, List<String>) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable(card.id) { mutableStateOf(card.name) }
    var favorite by rememberSaveable(card.id) { mutableStateOf(card.ui.favorite) }
    // The unticked ids, not the kept ones: a member that joins while the sheet is open must not be ungrouped by Save.
    var removed by rememberSaveable(card.id) { mutableStateOf(emptyList<String>()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TitleWithFavorite(stringResource(R.string.customize_group_title), favorite) { favorite = it }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.customize_group_name)) },
                placeholder = { Text(groupName(card.copy(name = ""))) },
                supportingText = { Text(stringResource(R.string.customize_group_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.customize_group_members), style = OikosTheme.type.label)
            for (member in card.members) {
                val checked = member.id !in removed
                Row(
                    Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch)
                        .toggleable(checked, role = Role.Checkbox) { removed = if (it) removed - member.id else removed + member.id },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Text(member.name, style = OikosTheme.type.body, modifier = Modifier.padding(start = 12.dp))
                }
            }
            Button(
                onClick = { onSave(name, favorite, card.members.map { it.id }.filter { it in removed }); onDismiss() },
                modifier = Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch),
            ) { Text(stringResource(R.string.customize_save)) }
            TextButton(onClick = { onSave(card.name, false, card.members.map { it.id }); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.customize_ungroup_all))
            }
        }
    }
}
