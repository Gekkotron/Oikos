package com.gekkotron.oikos.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.settings.R
import com.gekkotron.oikos.feature.settings.presentation.ShortcutRow

@Composable
fun VoiceShortcutsSection(shortcuts: List<ShortcutRow>, onDelete: (String) -> Unit) {
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.voice_shortcuts_section_title),
            style = OikosTheme.type.label,
            color = OikosTheme.colors.contentMuted,
            modifier = Modifier.semantics { heading() },
        )
        if (shortcuts.isEmpty()) {
            Text(stringResource(R.string.voice_shortcuts_empty), style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
        }
        shortcuts.forEach { row -> ShortcutItem(row, onDelete = { pending = row.id }) }
    }
    val target = shortcuts.firstOrNull { it.id == pending }
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.voice_shortcut_delete_confirm_title)) },
            text = { Text(stringResource(R.string.voice_shortcut_delete_confirm_body, target.phrase)) },
            confirmButton = { TextButton(onClick = { pending = null; onDelete(target.id) }) { Text(stringResource(R.string.voice_delete)) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.voice_cancel_action)) } },
        )
    }
}

@Composable
private fun ShortcutItem(row: ShortcutRow, onDelete: () -> Unit) {
    val deleteLabel = stringResource(R.string.voice_shortcut_delete_cd, row.phrase)
    Row(
        Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.phrase,
                style = OikosTheme.type.body,
                textDecoration = if (row.broken) TextDecoration.LineThrough else null,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.label, style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
                Text(row.lang.name, style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
                if (row.broken) Text(stringResource(R.string.voice_broken_label), style = OikosTheme.type.label, color = MaterialTheme.colorScheme.error)
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.semantics { contentDescription = deleteLabel }) {
            Text("\u2715", style = OikosTheme.type.body, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}
