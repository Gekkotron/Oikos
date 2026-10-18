package com.gekkotron.oikos.voice

import java.security.MessageDigest

data class VoiceShortcut(
    /** 32 lowercase hex characters — the first 16 bytes of SHA-256(lang + ":" + normalized phrase). */
    val id: String,
    /** As said by the user; for the shortcuts list display. */
    val phrase: String,
    val lang: VoiceLang,
    val intent: VoiceIntent,
) {
    companion object {
        fun id(lang: VoiceLang, normalizedPhrase: String): String {
            val input = "${lang.name.lowercase()}:$normalizedPhrase"
            val digest = MessageDigest.getInstance("SHA-256").digest(input.encodeToByteArray())
            return digest.take(16).joinToString("") { "%02x".format(it) }
        }
    }
}

data class ShortcutStatus(val shortcut: VoiceShortcut, val broken: Boolean)
