package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SuggesterTest {
    private val catalog = VoiceCatalogBuilder.build(
        lang = VoiceLang.FR,
        entities = listOf(
            entity("volet_salon", "Volet salon", Kind.COVER, "salon", position = true),
            entity("volet_salon_2", "Volet salon 2", Kind.COVER, "salon", position = true),
            entity("lampe_salon", "Lampe salon", Kind.LIGHT, "salon", brightness = true),
        ),
        areas = listOf(area("salon", "Salon")),
    )
    private val rules = RuleMatcher(catalog)
    private val suggester = Suggester(catalog, rules)

    @Test fun `at most max suggestions`() {
        assertTrue(suggester.suggest("quelque chose", max = 3).size <= 3)
    }

    @Test fun `a room-only phrase lists the room groups first`() {
        val labels = suggester.suggest("salon").map { it.label }
        assertTrue(labels.first().contains("Volet") || labels.first().contains("Lumière"), "first: ${labels.first()}")
    }

    @Test fun `a detected verb comes first in the ranking`() {
        val suggestions = suggester.suggest("ferme salon")
        assertEquals(VoiceAction.CLOSE, suggestions.first().intent.action)
    }

    @Test fun `no duplicate action-entities pair is produced`() {
        val list = suggester.suggest("salon", max = 20)
        val keys = list.map { it.intent.action to it.intent.entityIds.toSet() }
        assertEquals(keys.size, keys.toSet().size)
    }
}
