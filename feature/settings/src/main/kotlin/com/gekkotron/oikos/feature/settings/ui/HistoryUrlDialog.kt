package com.gekkotron.oikos.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.settings.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryUrlDialog(initial: String, hint: String, onSave: (String) -> Boolean, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var failed by rememberSaveable { mutableStateOf(false) }
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor, tonalElevation = AlertDialogDefaults.TonalElevation) {
            HistoryUrlDialogContent(
                text, failed, hint,
                onTextChange = { text = it; failed = false },
                onSave = { if (onSave(text)) onDismiss() else failed = true },
                onCancel = onDismiss,
            )
        }
    }
}

/** Stateless body of the history URL dialog, so it can be tested without a dialog window. */
@Composable
internal fun HistoryUrlDialogContent(text: String, failed: Boolean, hint: String, onTextChange: (String) -> Unit, onSave: () -> Unit, onCancel: () -> Unit) {
    val error = stringResource(R.string.settings_history_error)
    Column(Modifier.padding(24.dp)) {
        Text(stringResource(R.string.settings_history), style = OikosTheme.type.display)
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text(hint) },
            singleLine = true,
            isError = failed,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).semantics { if (failed) error(error) },
        )
        // Always laid out (blank until a refusal) so the dialog does not resize when the error appears.
        Text(if (failed) error else "", style = OikosTheme.type.label, color = MaterialTheme.colorScheme.error, minLines = 1, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_cancel)) }
            TextButton(onClick = onSave) { Text(stringResource(R.string.settings_history_save)) }
        }
    }
}
