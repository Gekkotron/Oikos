package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShortcutMatcherTest {
    private val catalog = VoiceCatalogBuilder.build(
        lang = VoiceLang.FR,
        entities = listOf(entity("lampe", "Lampe salon", Kind.LIGHT, "salon")),
        areas = listOf(area("salon", "Salon")),
    )

    @Test fun `id is deterministic for the same lang and normalized phrase`() {
        val a = VoiceShortcut.id(VoiceLang.FR, "ouvre volets salon")
        val b = VoiceShortcut.id(VoiceLang.FR, "ouvre volets salon")
        assertEquals(a, b)
    }

    @Test fun `id differs for the other language`() {
        val fr = VoiceShortcut.id(VoiceLang.FR, "open shutters living")
        val en = VoiceShortcut.id(VoiceLang.EN, "open shutters living")
        assertFalse(fr == en)
    }

    @Test fun `id is 32 hex chars`() {
        val id = VoiceShortcut.id(VoiceLang.FR, "x")
        assertTrue(id.matches(Regex("[0-9a-f]{32}")))
    }

    @Test fun `a matching normalized phrase returns the shortcut's intent`() {
        val sc = VoiceShortcut(
            id = VoiceShortcut.id(VoiceLang.FR, "il fait sombre salon"),
            phrase = "Il fait sombre dans le salon",
            lang = VoiceLang.FR,
            intent = VoiceIntent(VoiceAction.TURN_ON, listOf("lampe")),
        )
        val matcher = ShortcutMatcher(catalog, listOf(sc))
        val intent = matcher.resolve("Il fait sombre dans le salon.")
        assertNotNull(intent)
        assertEquals(VoiceAction.TURN_ON, intent.action)
        assertEquals(listOf("lampe"), intent.entityIds)
    }

    @Test fun `shortcuts of the other language are ignored and preserved`() {
        val other = VoiceShortcut(
            id = VoiceShortcut.id(VoiceLang.EN, "turn on lamp"),
            phrase = "Turn on the lamp",
            lang = VoiceLang.EN,
            intent = VoiceIntent(VoiceAction.TURN_ON, listOf("lampe")),
        )
        val matcher = ShortcutMatcher(catalog, listOf(other))
        assertNull(matcher.resolve("Turn on the lamp"))
        // but still listed with its lang chip
        val statuses = matcher.status()
        assertEquals(1, statuses.size)
        assertEquals(VoiceLang.EN, statuses.first().shortcut.lang)
        assertFalse(statuses.first().broken)
    }

    @Test fun `a shortcut with no surviving entity id is broken and never matches`() {
        val sc = VoiceShortcut(
            id = VoiceShortcut.id(VoiceLang.FR, "la fete"),
            phrase = "La fête",
            lang = VoiceLang.FR,
            intent = VoiceIntent(VoiceAction.TURN_ON, listOf("ghost")),
        )
        val matcher = ShortcutMatcher(catalog, listOf(sc))
        assertNull(matcher.resolve("La fête"))
        assertTrue(matcher.status().first().broken)
    }
}
