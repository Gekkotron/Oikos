package com.gekkotron.oikos.source

import com.gekkotron.oikos.voice.VoiceAction
import com.gekkotron.oikos.voice.VoiceLang
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceShortcutDocumentTest {
    @Test fun `encode round-trips`() {
        val doc = VoiceShortcutDocument(
            phrase = "il fait sombre dans le salon",
            lang = "fr",
            action = VoiceAction.TURN_ON.name,
            entityIds = listOf("lampe"),
            value = null,
        )
        val json = VoiceShortcutDocument.encode(doc)
        assertEquals(doc, VoiceShortcutDocument.decode(json))
    }

    @Test fun `decode ignores unknown keys`() {
        val json = """{"v":1,"phrase":"x","lang":"fr","action":"TURN_ON","entityIds":["a"],"value":null,"future":42}"""
        val doc = VoiceShortcutDocument.decode(json)!!
        assertEquals("x", doc.phrase)
    }

    @Test fun `decode returns null on invalid json`() {
        assertNull(VoiceShortcutDocument.decode("not json"))
    }

    @Test fun `toShortcut maps to a VoiceShortcut using the id hash`() {
        val doc = VoiceShortcutDocument(phrase = "Il fait sombre", lang = "fr", action = "TURN_ON", entityIds = listOf("x"))
        val sc = doc.toShortcut()
        assertEquals(VoiceLang.FR, sc!!.lang)
        assertEquals(VoiceAction.TURN_ON, sc.intent.action)
    }

    @Test fun `toShortcut returns null on unknown lang`() {
        val doc = VoiceShortcutDocument(phrase = "x", lang = "zz", action = "TURN_ON", entityIds = listOf("x"))
        assertNull(doc.toShortcut())
    }

    @Test fun `toShortcut returns null on unknown action`() {
        val doc = VoiceShortcutDocument(phrase = "x", lang = "fr", action = "BOGUS", entityIds = listOf("x"))
        assertNull(doc.toShortcut())
    }
}
