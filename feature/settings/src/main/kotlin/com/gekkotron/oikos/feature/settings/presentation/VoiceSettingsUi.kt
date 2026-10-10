package com.gekkotron.oikos.feature.settings.presentation

import com.gekkotron.oikos.feature.voice.SpeechAvailability
import com.gekkotron.oikos.voice.VoiceLang

data class ShortcutRow(val id: String, val phrase: String, val lang: VoiceLang, val label: String, val broken: Boolean)

data class VoiceSettingsUi(
    val enabled: Boolean = false,
    val availability: SpeechAvailability.Status = SpeechAvailability.Status.Ready,
    val shortcuts: List<ShortcutRow> = emptyList(),
)
