package com.gekkotron.oikos.voice

import java.text.Normalizer

object VoiceText {
    private val DIACRITICS = Regex("\\p{Mn}+")
    private val NON_TEXT = Regex("[^a-z0-9%\\s]")
    private val LETTERS = Regex("[a-z]+")
    private val POLITE_FR = Regex("""\bs il (?:te|vous) plait\b""")
    private val POLITE_EN = Regex("""\bplease\b""")

    fun prepare(text: String, lang: VoiceLang): String {
        var s = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(DIACRITICS, "")
        s = s.replace(NON_TEXT, " ")
        for ((re, to) in VoiceLexicon.of(lang).phrases) s = re.replace(s, to)
        return s.trim()
    }

    fun words(prepared: String): List<String> =
        LETTERS.findAll(prepared).map { it.value }.filter { it.length >= 2 }.toList()

    /** Normalized shortcut key: no accents, no punctuation, no filler words. */
    fun normalizeShortcut(text: String, lang: VoiceLang): String {
        val table = VoiceLexicon.of(lang)
        val folded = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .let { f -> table.phrases.fold(f) { acc, (re, to) -> re.replace(acc, to) } }
        val polite = (if (lang == VoiceLang.FR) POLITE_FR else POLITE_EN).replace(folded, " ")
        return polite.split(' ').filter { it.isNotEmpty() && it !in table.shortcutStopWords }.joinToString(" ")
    }
}
