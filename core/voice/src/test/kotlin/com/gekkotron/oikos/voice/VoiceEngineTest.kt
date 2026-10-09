package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VoiceEngineTest {
    private val catalog = VoiceCatalogBuilder.build(
        lang = VoiceLang.FR,
        entities = listOf(
            entity("volet_salon_1", "Volet salon 1", Kind.COVER, "salon", position = true),
            entity("volet_salon_2", "Volet salon 2", Kind.COVER, "salon", position = true),
            entity("lampe_salon", "Lampe salon", Kind.LIGHT, "salon"),
        ),
        areas = listOf(area("salon", "Salon")),
    )

    @Test fun `shortcuts are tried first`() {
        val sc = VoiceShortcut(
            id = VoiceShortcut.id(VoiceLang.FR, "il fait sombre salon"),
            phrase = "Il fait sombre dans le salon",
            lang = VoiceLang.FR,
            intent = VoiceIntent(VoiceAction.TURN_ON, listOf("lampe_salon")),
        )
        val engine = VoiceEngine(catalog, listOf(sc))
        val resolution = engine.resolve("Il fait sombre dans le salon")
        val understood = assertIs<VoiceResolution.Understood>(resolution)
        assertEquals(VoiceSource.SHORTCUT, understood.source)
        assertEquals(listOf("lampe_salon"), understood.intent.entityIds)
    }

    @Test fun `rules fire when no shortcut matches`() {
        val resolution = VoiceEngine(catalog, emptyList()).resolve("ouvre les volets du salon")
        val understood = assertIs<VoiceResolution.Understood>(resolution)
        assertEquals(VoiceSource.RULES, understood.source)
        assertEquals(VoiceAction.OPEN, understood.intent.action)
    }

    @Test fun `not understood returns ranked suggestions`() {
        val resolution = VoiceEngine(catalog, emptyList()).resolve("salon")
        val not = assertIs<VoiceResolution.NotUnderstood>(resolution)
        assertTrue(not.suggestions.isNotEmpty())
    }
}
