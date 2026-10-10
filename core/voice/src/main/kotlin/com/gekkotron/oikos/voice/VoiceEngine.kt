package com.gekkotron.oikos.voice

/** Tries shortcuts, then rules; falls back to suggestions when neither understands. */
class VoiceEngine(
    private val catalog: VoiceCatalog,
    private val shortcuts: List<VoiceShortcut>,
) {
    private val shortcutMatcher = ShortcutMatcher(catalog, shortcuts)
    private val ruleMatcher = RuleMatcher(catalog)
    private val suggester = Suggester(catalog, ruleMatcher)

    fun resolve(text: String): VoiceResolution {
        shortcutMatcher.resolve(text)?.let { return VoiceResolution.Understood(it, VoiceSource.SHORTCUT) }
        ruleMatcher.resolve(text)?.let { return VoiceResolution.Understood(it, VoiceSource.RULES) }
        return VoiceResolution.NotUnderstood(suggester.suggest(text))
    }
}
