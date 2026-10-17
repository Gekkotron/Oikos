package com.gekkotron.oikos.voice

class ShortcutMatcher(
    private val catalog: VoiceCatalog,
    private val shortcuts: List<VoiceShortcut>,
) {
    private val sameLang = shortcuts.filter { it.lang == catalog.lang }
    private val byKey: Map<String, VoiceShortcut> =
        sameLang.associateBy { VoiceText.normalizeShortcut(it.phrase, catalog.lang) }

    fun resolve(text: String): VoiceIntent? {
        val key = VoiceText.normalizeShortcut(text, catalog.lang)
        if (key.isEmpty()) return null
        val sc = byKey[key] ?: return null
        return if (isBroken(sc)) null else sc.intent
    }

    fun status(): List<ShortcutStatus> = shortcuts.map { ShortcutStatus(it, broken = isBroken(it)) }

    private fun isBroken(sc: VoiceShortcut): Boolean {
        val survivors = sc.intent.entityIds.filter { catalog.entity(it) != null }
        if (survivors.isEmpty()) return true
        if (sc.intent.action == VoiceAction.QUERY_STATE) return false
        return CommandMapper.map(sc.intent.copy(entityIds = survivors), catalog).isEmpty()
    }
}
