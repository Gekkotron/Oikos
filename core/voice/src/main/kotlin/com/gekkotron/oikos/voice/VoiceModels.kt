package com.gekkotron.oikos.voice

enum class VoiceAction { TURN_ON, TURN_OFF, OPEN, CLOSE, STOP, SET_POSITION, SET_BRIGHTNESS, ARM_AWAY, ARM_HOME, DISARM }

enum class VoiceLang {
    FR, EN;

    companion object {
        fun fromAppLocale(languageTag: String): VoiceLang =
            if (languageTag.lowercase().startsWith("fr")) FR else EN
    }
}

data class VoiceIntent(val action: VoiceAction, val entityIds: List<String>, val value: Int? = null) {
    init { require(value == null || value in 0..100) { "value must be 0..100" } }
}

enum class VoiceSource { SHORTCUT, RULES }

data class Suggestion(val intent: VoiceIntent, val label: String)

sealed interface VoiceResolution {
    data class Understood(val intent: VoiceIntent, val source: VoiceSource) : VoiceResolution
    data class NotUnderstood(val suggestions: List<Suggestion>) : VoiceResolution
}
