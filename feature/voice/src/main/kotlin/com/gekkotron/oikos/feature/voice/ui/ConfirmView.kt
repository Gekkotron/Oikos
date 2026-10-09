package com.gekkotron.oikos.feature.voice.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.alarm.CodeKeypadSheet
import com.gekkotron.oikos.feature.voice.R
import com.gekkotron.oikos.feature.voice.VoiceSessionState
import com.gekkotron.oikos.voice.VoiceAction
import com.gekkotron.oikos.voice.VoiceLexicon

@Composable
internal fun ColumnScope.ConfirmView(
    state: VoiceSessionState.ConfirmNeeded,
    onConfirm: (Boolean) -> Unit,
    onCode: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val lang = rememberVoiceLang()
    val label = VoiceLexicon.actionLabel(lang, state.intent.action)
    val question = when (state.intent.action) {
        VoiceAction.DISARM -> stringResource(R.string.voice_confirm_disarm)
        VoiceAction.ARM_AWAY -> stringResource(R.string.voice_confirm_arm_away)
        VoiceAction.ARM_HOME -> stringResource(R.string.voice_confirm_arm_home)
        else -> "$label ?"
    }
    var keypad by remember { mutableStateOf(false) }
    Text(question, style = OikosTheme.type.display, color = OikosTheme.colors.content, textAlign = TextAlign.Center)
    // The positive button carries the action label; with a code format it opens the keypad first.
    VoiceRow(label, onClick = { if (state.codeFormat != null) keypad = true else onConfirm(true) })
    VoiceRow(stringResource(R.string.voice_no), onClick = { onConfirm(false) })
    val format = state.codeFormat
    if (keypad && format != null) {
        CodeKeypadSheet(
            format = format,
            onSubmit = { code ->
                keypad = false
                onCode(code)
                onConfirm(true)
            },
            onDismiss = onCancel,
        )
    }
}
