package com.gekkotron.oikos.feature.voice.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.voice.R
import com.gekkotron.oikos.voice.Suggestion

@Composable
internal fun ColumnScope.NotUnderstoodView(
    suggestions: List<Suggestion>,
    onPick: (Suggestion) -> Unit,
    onCancel: () -> Unit,
) {
    Text(stringResource(R.string.voice_not_understood), style = OikosTheme.type.display, color = OikosTheme.colors.content)
    suggestions.forEach { s -> VoiceRow(s.label, onClick = { onPick(s) }) }
    // TODO: full "Other..." picker (action, entity, value); for now it dismisses the sheet.
    VoiceRow(stringResource(R.string.voice_other), onCancel)
    VoiceRow(stringResource(R.string.voice_ignore), onCancel)
}
