package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RuleMatcherQueryTest {
    private val fr = RuleMatcher(
        VoiceCatalogBuilder.build(
            lang = VoiceLang.FR,
            entities = listOf(
                entity("temp_salon", "Température salon", Kind.SENSOR, "salon", deviceClass = "temperature", unit = "°C"),
                entity("hum_salon", "Humidité salon", Kind.SENSOR, "salon", deviceClass = "humidity", unit = "%"),
                entity("door_salon", "Porte salon", Kind.BINARY_SENSOR, "salon", deviceClass = "door"),
                entity("temp_bureau", "Température bureau", Kind.SENSOR, "office", deviceClass = "temperature", unit = "°C"),
                entity("hum_bureau", "Humidité bureau", Kind.SENSOR, "office", deviceClass = "humidity", unit = "%"),
                entity("volet_salon", "Volet salon", Kind.COVER, "salon", position = true),
                entity("volet_cuisine", "Volet cuisine", Kind.COVER, "kitchen", position = true),
                entity("lampe_salon", "Lampe salon", Kind.LIGHT, "salon", brightness = true),
                entity("lampe_bureau", "Lampe bureau", Kind.LIGHT, "office", brightness = true),
            ),
            areas = listOf(area("salon", "Salon"), area("kitchen", "Cuisine"), area("office", "Bureau")),
        ),
    )

    private val en = RuleMatcher(
        VoiceCatalogBuilder.build(
            lang = VoiceLang.EN,
            entities = listOf(
                entity("temp_living", "Living room temperature", Kind.SENSOR, "living", deviceClass = "temperature"),
                entity("temp_office", "Office temperature", Kind.SENSOR, "office", deviceClass = "temperature"),
                entity("shutter_living", "Living room shutter", Kind.COVER, "living", position = true),
                entity("shutter_kitchen", "Kitchen shutter", Kind.COVER, "kitchen", position = true),
            ),
            areas = listOf(area("living", "Living room"), area("office", "Office"), area("kitchen", "Kitchen")),
        ),
    )

    @Test fun `quelle est la temperature du salon targets only the salon temperature sensor`() {
        val i = fr.resolve("quelle est la température du salon")
        assertNotNull(i)
        assertEquals(VoiceAction.QUERY_STATE, i.action)
        assertEquals(listOf("temp_salon"), i.entityIds)
    }

    @Test fun `combien d humidite dans le bureau targets only the office humidity sensor`() {
        val i = fr.resolve("combien d'humidité dans le bureau")
        assertNotNull(i)
        assertEquals(listOf("hum_bureau"), i.entityIds)
    }

    @Test fun `est-ce que les volets sont fermes resolves to QUERY_STATE on every cover`() {
        val i = fr.resolve("est-ce que les volets sont fermés")
        assertNotNull(i)
        assertEquals(VoiceAction.QUERY_STATE, i.action)
        assertEquals(setOf("volet_salon", "volet_cuisine"), i.entityIds.toSet())
    }

    @Test fun `les lumieres sont elles allumees resolves to QUERY_STATE on every light`() {
        val i = fr.resolve("les lumières sont elles allumées")
        assertNotNull(i)
        assertEquals(VoiceAction.QUERY_STATE, i.action)
        assertEquals(setOf("lampe_salon", "lampe_bureau"), i.entityIds.toSet())
    }

    @Test fun `fermes sets hint to EXPECT_CLOSED`() {
        assertEquals(QueryHint.EXPECT_CLOSED, fr.resolve("est-ce que les volets sont fermés")?.hint)
    }

    @Test fun `allumee sets hint to EXPECT_ON`() {
        assertEquals(QueryHint.EXPECT_ON, fr.resolve("les lumières sont elles allumées")?.hint)
    }

    @Test fun `plain quelle temperature leaves hint null`() {
        val i = fr.resolve("quelle température")
        assertNotNull(i)
        assertNull(i.hint)
    }

    @Test fun `attribute wins over device word and narrows candidates to the matching device-class`() {
        val i = fr.resolve("quelle est l'humidité du salon")
        assertNotNull(i)
        assertEquals(listOf("hum_salon"), i.entityIds)
    }

    @Test fun `what is the temperature in the living room targets the living temperature sensor`() {
        val i = en.resolve("what is the temperature in the living room")
        assertNotNull(i)
        assertEquals(VoiceAction.QUERY_STATE, i.action)
        assertEquals(listOf("temp_living"), i.entityIds)
    }

    @Test fun `are the shutters closed resolves to QUERY_STATE with EXPECT_CLOSED`() {
        val i = en.resolve("are the shutters closed")
        assertNotNull(i)
        assertEquals(VoiceAction.QUERY_STATE, i.action)
        assertEquals(QueryHint.EXPECT_CLOSED, i.hint)
        assertEquals(setOf("shutter_living", "shutter_kitchen"), i.entityIds.toSet())
    }

    @Test fun `quelle est la temperature returns every temperature sensor`() {
        val i = fr.resolve("quelle est la température")
        assertNotNull(i)
        assertEquals(setOf("temp_salon", "temp_bureau"), i.entityIds.toSet())
    }

    @Test fun `ferme les volets du salon still resolves to CLOSE`() {
        val i = fr.resolve("ferme les volets du salon")
        assertNotNull(i)
        assertEquals(VoiceAction.CLOSE, i.action)
        assertNull(i.hint)
    }

    @Test fun `allume la lumiere du salon still resolves to TURN_ON`() {
        assertEquals(VoiceAction.TURN_ON, fr.resolve("allume la lumière du salon")?.action)
    }

    @Test fun `dis allume la lumiere resolves to TURN_ON`() {
        assertEquals(VoiceAction.TURN_ON, fr.resolve("dis allume la lumière du salon")?.action)
    }

    @Test fun `detectAction maps a query phrase to QUERY_STATE`() {
        assertEquals(VoiceAction.QUERY_STATE, fr.detectAction("quelle est la température du salon"))
    }
}
