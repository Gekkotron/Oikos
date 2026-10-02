package com.gekkotron.oikos.designsystem.alarm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.R
import com.gekkotron.oikos.model.CodeFormat

private const val DELETE = "⌫"
private const val BLANK = ""
private val KEYS = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(BLANK, "0", DELETE))

/**
 * Asks for the alarm code (security spec §4.2). The code lives in plain `remember`, never saved state, so it
 * is gone once the sheet leaves composition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeKeypadSheet(format: CodeFormat, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.alarm_code), style = OikosTheme.type.display.copy(fontSize = 22.sp))
            when (format) {
                CodeFormat.NUMBER -> {
                    val entered = stringResource(R.string.alarm_code_entered, code.length)
                    Text(
                        "•".repeat(code.length),
                        style = OikosTheme.type.display.copy(fontSize = 28.sp),
                        modifier = Modifier.testTag("keypad-dots").heightIn(min = 40.dp).semantics { stateDescription = entered },
                    )
                    for (row in KEYS) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (key in row) {
                                if (key == BLANK) {
                                    Spacer(Modifier.size(72.dp))
                                    continue
                                }
                                val deleteLabel = stringResource(R.string.alarm_code_delete)
                                FilledTonalButton(
                                    onClick = { code = if (key == DELETE) code.dropLast(1) else code + key },
                                    modifier = Modifier.size(72.dp).then(
                                        if (key == DELETE) Modifier.semantics { contentDescription = deleteLabel } else Modifier,
                                    ),
                                ) { Text(key, fontSize = 22.sp) }
                            }
                        }
                    }
                }
                CodeFormat.TEXT -> OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(stringResource(R.string.alarm_code)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("keypad-dots"),
                )
            }
            Button(
                onClick = { val typed = code; code = ""; onSubmit(typed) },
                enabled = code.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch),
            ) { Text(stringResource(R.string.alarm_code_ok)) }
        }
    }
}
