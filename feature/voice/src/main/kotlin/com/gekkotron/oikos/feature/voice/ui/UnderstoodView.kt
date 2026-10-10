package com.gekkotron.oikos.feature.voice.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.voice.R
import com.gekkotron.oikos.feature.voice.VoiceSessionState
import com.gekkotron.oikos.voice.VoiceLexicon

/** Renders the brief Executing loader or the Done summary. */
@Composable
internal fun ColumnScope.UnderstoodView(
    executing: VoiceSessionState.Executing?,
    done: VoiceSessionState.Done?,
    onWrong: () -> Unit,
) {
    val c = OikosTheme.colors
    val lang = rememberVoiceLang()
    if (done == null) {
        CircularProgressIndicator(color = c.warning)
        return
    }
    val result = done.result
    val label = VoiceLexicon.actionLabel(lang, result.intent.action)
    val nothingRan = result.succeeded.isEmpty() && result.failed.isEmpty()
    when {
        nothingRan -> Text(label + " · ✗", style = OikosTheme.type.body, color = c.contentMuted, textAlign = TextAlign.Center)
        result.succeeded.isEmpty() -> {
            Text("✗ $label", style = OikosTheme.type.display, color = c.offline, textAlign = TextAlign.Center)
        }
        else -> Text(
            "✓ $label · ${result.succeeded.size}",
            style = OikosTheme.type.display,
            color = c.content,
            textAlign = TextAlign.Center,
        )
    }
    result.failed.forEach { (id, reason) ->
        Text("$id: $reason", style = OikosTheme.type.cardName, color = c.offline, textAlign = TextAlign.Center)
    }
    VoiceRow(stringResource(R.string.voice_wrong), onWrong)
}
