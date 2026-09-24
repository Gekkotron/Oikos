package com.gekkotron.oikos.feature.onboarding.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.data.DEFAULT_PREFIX
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.onboarding.FormField
import com.gekkotron.oikos.feature.onboarding.ManualForm
import com.gekkotron.oikos.feature.onboarding.R

@Composable
internal fun ManualScreen(form: ManualForm, errors: Set<FormField>, onChange: (ManualForm) -> Unit, onSubmit: () -> Unit, onBack: () -> Unit) {
    SecureWindow()
    var advanced by rememberSaveable { mutableStateOf(form.prefix != DEFAULT_PREFIX) }
    OnboardingColumn {
        Text(stringResource(R.string.manual_title), style = OikosTheme.type.display)
        Field(form.host, { onChange(form.copy(host = it)) }, R.string.manual_host, R.string.manual_host_error.takeIf { FormField.HOST in errors }, KeyboardType.Uri)
        Field(form.port, { onChange(form.copy(port = it)) }, R.string.manual_port, R.string.manual_port_error.takeIf { FormField.PORT in errors }, KeyboardType.Number)
        SwitchRow(R.string.manual_tls, form.tls) { onChange(form.copy(tls = it)) }
        SwitchRow(R.string.manual_ws, form.ws) { onChange(form.copy(ws = it)) }
        if (form.plainTextWarning) {
            Text(stringResource(R.string.manual_plain_warning), style = OikosTheme.type.body, color = OikosTheme.colors.warning)
        }
        Field(form.username, { onChange(form.copy(username = it)) }, R.string.manual_username, null, KeyboardType.Text)
        Field(form.password, { onChange(form.copy(password = it)) }, R.string.manual_password, null, KeyboardType.Password, secret = true)
        TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.manual_advanced)) }
        if (advanced) {
            Field(form.prefix, { onChange(form.copy(prefix = it)) }, R.string.manual_prefix, R.string.manual_prefix_error.takeIf { FormField.PREFIX in errors }, KeyboardType.Text)
        }
        Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.manual_connect)) }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_back)) }
    }
}

@Composable
private fun Field(
    value: String,
    onValue: (String) -> Unit,
    @StringRes label: Int,
    @StringRes error: Int?,
    keyboard: KeyboardType,
    secret: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(stringResource(label)) },
        isError = error != null,
        supportingText = error?.let { { Text(stringResource(it)) } },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Next, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(@StringRes label: Int, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChecked),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), style = OikosTheme.type.body, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
