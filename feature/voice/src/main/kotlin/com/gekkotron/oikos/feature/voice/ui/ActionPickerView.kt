package com.gekkotron.oikos.feature.voice.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.voice.R
import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.voice.VoiceAction
import com.gekkotron.oikos.voice.VoiceEntity
import com.gekkotron.oikos.voice.VoiceLexicon

/** Control actions offered per kind; value-requiring actions (brightness, position) are left out of picker v1. */
private fun controlActions(entity: VoiceEntity): List<VoiceAction> = when (entity.kind) {
    Kind.LIGHT, Kind.SWITCH -> listOf(VoiceAction.TURN_ON, VoiceAction.TURN_OFF)
    Kind.COVER -> listOf(VoiceAction.OPEN, VoiceAction.CLOSE, VoiceAction.STOP)
    Kind.ALARM_PANEL -> buildList {
        if (AlarmMode.HOME in entity.alarmModes) add(VoiceAction.ARM_HOME)
        if (AlarmMode.AWAY in entity.alarmModes) add(VoiceAction.ARM_AWAY)
        add(VoiceAction.DISARM)
    }
    else -> emptyList()
}

private fun readOnly(kind: Kind): Boolean = when (kind) {
    Kind.SENSOR, Kind.BINARY_SENSOR, Kind.CLIMATE, Kind.VACUUM, Kind.WATER_HEATER, Kind.NUMBER -> true
    else -> false
}

@Composable
internal fun ColumnScope.ActionPickerView(
    entity: VoiceEntity,
    areaName: String,
    heardText: String,
    onPickAction: (VoiceAction, remember: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val c = OikosTheme.colors
    var rememberPhrase by remember { mutableStateOf(true) }
    Text(entity.displayName, style = OikosTheme.type.display, color = c.content)
    Text(areaName, style = OikosTheme.type.label, color = c.contentMuted)
    FilterChip(
        selected = rememberPhrase,
        onClick = { rememberPhrase = !rememberPhrase },
        label = { Text(stringResource(R.string.voice_picker_remember_phrase)) },
    )
    if (readOnly(entity.kind)) {
        VoiceRow(stringResource(R.string.voice_picker_read_value), { onPickAction(VoiceAction.QUERY_STATE, rememberPhrase) })
    } else {
        VoiceRow(stringResource(R.string.voice_picker_read_state), { onPickAction(VoiceAction.QUERY_STATE, rememberPhrase) })
        val lang = rememberVoiceLang()
        controlActions(entity).forEach { a ->
            VoiceRow(VoiceLexicon.actionLabel(lang, a), { onPickAction(a, rememberPhrase) })
        }
    }
    VoiceRow(stringResource(R.string.voice_picker_back), onBack)
}
