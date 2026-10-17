package com.gekkotron.oikos.feature.settings.ui

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.settings.R
import com.gekkotron.oikos.feature.settings.presentation.VoiceSettingsUi
import com.gekkotron.oikos.feature.voice.SpeechAvailability
import com.gekkotron.oikos.feature.voice.TtsAvailability

@Composable
fun VoiceSection(ui: VoiceSettingsUi, onToggle: (Boolean) -> Unit) {
    val ready = ui.availability == SpeechAvailability.Status.Ready
    val reason = when (ui.availability) {
        SpeechAvailability.Status.Ready -> null
        SpeechAvailability.Status.NeedsApi31 -> stringResource(R.string.voice_availability_needs_api_31)
        SpeechAvailability.Status.NoOnDevice -> stringResource(R.string.voice_availability_no_on_device)
        is SpeechAvailability.Status.LanguagePackMissing -> stringResource(R.string.voice_availability_language_pack_missing, ui.availability.tag)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.voice_section_title),
            style = OikosTheme.type.label,
            color = OikosTheme.colors.contentMuted,
            modifier = Modifier.semantics { heading() },
        )
        Row(
            Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch)
                .toggleable(value = ui.enabled && ready, enabled = ready, role = Role.Switch, onValueChange = onToggle)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.voice_toggle_label), style = OikosTheme.type.body, modifier = Modifier.weight(1f))
            Switch(checked = ui.enabled && ready, onCheckedChange = null, enabled = ready)
        }
        if (reason != null) Text(reason, style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
        TtsLine(ui.ttsAvailability)
    }
}

@Composable
private fun TtsLine(availability: TtsAvailability) {
    val status = when (availability) {
        TtsAvailability.Unknown, TtsAvailability.Ready -> R.string.settings_voice_tts_ready
        TtsAvailability.LanguageMissing -> R.string.settings_voice_tts_language_missing
        TtsAvailability.EngineMissing -> R.string.settings_voice_tts_engine_missing
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_voice_tts_label), style = OikosTheme.type.body, modifier = Modifier.weight(1f))
            Text(stringResource(status), style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
        }
        if (availability == TtsAvailability.LanguageMissing) {
            Text(
                stringResource(R.string.settings_voice_tts_download_action),
                style = OikosTheme.type.body,
                modifier = Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch)
                    .clickable(role = Role.Button) { launcher.launch(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
                    .padding(vertical = 12.dp),
            )
        }
    }
}
