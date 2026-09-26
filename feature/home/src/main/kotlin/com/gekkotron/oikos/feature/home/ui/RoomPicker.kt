package com.gekkotron.oikos.feature.home.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.UNASSIGNED_AREA

/**
 * Rooms as radio rows, then "No room", then a "New room" field (spec §5.2). Shared by the override
 * sheet and the Rooms step. [onCreate] returns the id of the room to select, or null if the name was refused.
 */
@Composable
fun RoomPicker(rooms: List<Area>, selectedId: String?, onSelect: (String) -> Unit, onCreate: (String) -> String?, modifier: Modifier = Modifier) {
    var newName by rememberSaveable { mutableStateOf("") }
    val add = {
        onCreate(newName)?.let {
            newName = ""
            onSelect(it)
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.selectableGroup()) {
            rooms.forEach { room -> RadioRow(room.name, room.id == selectedId) { onSelect(room.id) } }
            RadioRow(stringResource(R.string.room_none), selectedId == UNASSIGNED_AREA) { onSelect(UNASSIGNED_AREA) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text(stringResource(R.string.room_new)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = { add() }, enabled = newName.isNotBlank(), modifier = Modifier.heightIn(min = OikosDimens.minTouch)) {
                Text(stringResource(R.string.room_add))
            }
        }
    }
}

@Composable
internal fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = OikosTheme.type.body)
    }
}
