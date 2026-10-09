package com.gekkotron.oikos.source

import com.gekkotron.oikos.model.OikosJson
import com.gekkotron.oikos.voice.VoiceAction
import com.gekkotron.oikos.voice.VoiceIntent
import com.gekkotron.oikos.voice.VoiceLang
import com.gekkotron.oikos.voice.VoiceShortcut
import kotlinx.serialization.Serializable

/** Retained JSON payload on `<prefix>/voice/shortcut/<id>` (voice spec §4). */
@Serializable
data class VoiceShortcutDocument(
    val v: Int = 1,
    val phrase: String,
    val lang: String,
    val action: String,
    val entityIds: List<String>,
    val value: Int? = null,
) {
    fun toShortcut(id: String? = null): VoiceShortcut? {
        val voiceLang = when (lang) {
            "fr" -> VoiceLang.FR
            "en" -> VoiceLang.EN
            else -> return null
        }
        val voiceAction = VoiceAction.entries.firstOrNull { it.name == action } ?: return null
        return VoiceShortcut(
            id = id ?: VoiceShortcut.id(voiceLang, phrase),
            phrase = phrase,
            lang = voiceLang,
            intent = VoiceIntent(voiceAction, entityIds, value),
        )
    }

    companion object {
        fun encode(doc: VoiceShortcutDocument): String = OikosJson.encodeToString(serializer(), doc)
        fun decode(payload: String): VoiceShortcutDocument? =
            runCatching { OikosJson.decodeFromString(serializer(), payload) }.getOrNull()
    }
}
