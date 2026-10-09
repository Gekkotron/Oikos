package com.gekkotron.oikos.feature.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.border
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.voice.ErrorKind
import com.gekkotron.oikos.feature.voice.R
import com.gekkotron.oikos.feature.voice.VoiceSessionState
import com.gekkotron.oikos.voice.Suggestion
import com.gekkotron.oikos.voice.VoiceLang
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun rememberVoiceLang(): VoiceLang {
    val tag = LocalConfiguration.current.locales[0].toLanguageTag()
    return remember(tag) { VoiceLang.fromAppLocale(tag) }
}

/** Tappable pill used for every action in the sheet. */
@Composable
internal fun VoiceRow(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false) {
    val c = OikosTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.chip)
            .border(1.dp, c.chipBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = OikosTheme.type.body, color = if (danger) c.offline else c.content)
    }
}

@Composable
fun VoiceSheet(
    state: StateFlow<VoiceSessionState>,
    onPick: (Suggestion) -> Unit,
    onConfirm: (Boolean) -> Unit,
    onCode: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val current = state.collectAsStateWithLifecycle().value
    val c = OikosTheme.colors
    Box(
        Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onCancel,
        ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(c.backgroundBottom)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (current) {
                is VoiceSessionState.Listening -> ListeningView(current.partial)
                is VoiceSessionState.Executing -> UnderstoodView(executing = current, done = null, onWrong = onCancel)
                is VoiceSessionState.Done -> UnderstoodView(executing = null, done = current, onWrong = onCancel)
                is VoiceSessionState.NotUnderstood -> NotUnderstoodView(current.suggestions, onPick, onCancel)
                is VoiceSessionState.ConfirmNeeded -> ConfirmView(current, onConfirm, onCode, onCancel)
                is VoiceSessionState.Error -> {
                    Text(
                        stringResource(errorRes(current.kind)),
                        style = OikosTheme.type.body,
                        color = c.content,
                        textAlign = TextAlign.Center,
                    )
                    VoiceRow(stringResource(R.string.voice_cancel), onCancel)
                }
            }
        }
    }
}

private fun errorRes(kind: ErrorKind): Int = when (kind) {
    ErrorKind.MQTT_DOWN -> R.string.voice_mqtt_down
    ErrorKind.SPEECH -> R.string.voice_speech_error
    ErrorKind.PERMISSION -> R.string.voice_permission_denied
    ErrorKind.SILENCE -> R.string.voice_silence
    ErrorKind.UNKNOWN -> R.string.voice_unknown_error
}
