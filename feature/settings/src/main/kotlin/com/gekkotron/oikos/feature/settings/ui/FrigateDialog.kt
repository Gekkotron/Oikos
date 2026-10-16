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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.settings.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FrigateDialog(initialUrl: String, initialUser: String, hasPassword: Boolean, onSave: (String, String, String) -> Boolean, onDismiss: () -> Unit) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var user by rememberSaveable { mutableStateOf(initialUser) }
    // Not saveable: a password must not land in the saved instance state.
    var password by remember { mutableStateOf("") }
    var failed by rememberSaveable { mutableStateOf(false) }
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor, tonalElevation = AlertDialogDefaults.TonalElevation) {
            FrigateDialogContent(
                url, user, initialUser, password, hasPassword, failed,
                onUrl = { url = it; failed = false },
                onUser = { user = it },
                onPassword = { password = it },
                onSave = { if (onSave(url, user, password)) onDismiss() else failed = true },
                onCancel = onDismiss,
            )
        }
    }
}

/** Stateless body of the Frigate dialog, so it can be tested without a dialog window. */
@Composable
internal fun FrigateDialogContent(
    url: String,
    user: String,
    initialUser: String,
    password: String,
    hasPassword: Boolean,
    failed: Boolean,
    onUrl: (String) -> Unit,
    onUser: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val error = stringResource(R.string.settings_history_error)
    Column(Modifier.padding(24.dp)) {
        Text(stringResource(R.string.settings_frigate), style = OikosTheme.type.display)
        OutlinedTextField(
            value = url,
            onValueChange = onUrl,
            label = { Text(stringResource(R.string.settings_frigate_hint)) },
            singleLine = true,
            isError = failed,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).semantics { if (failed) error(error) },
        )
        // Always laid out (blank until a refusal) so the dialog does not resize when the error appears.
        Text(if (failed) error else "", style = OikosTheme.type.label, color = MaterialTheme.colorScheme.error, minLines = 1, modifier = Modifier.padding(top = 4.dp))
        OutlinedTextField(
            value = user,
            onValueChange = onUser,
            label = { Text(stringResource(R.string.settings_frigate_user)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPassword,
            label = { Text(stringResource(R.string.settings_frigate_password)) },
            // The stored password is kept only for the same user (see the ViewModel).
            supportingText = if (hasPassword && user.trim() == initialUser.trim()) ({ Text(stringResource(R.string.settings_frigate_unchanged)) }) else null,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_cancel)) }
            TextButton(onClick = onSave) { Text(stringResource(R.string.settings_history_save)) }
        }
    }
}
