package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceCatalogTest {
    private val areas = listOf(area("salon", "Salon"), area("remi", "Rémi"), area("kitchen", "Cuisine"))

    @Test fun `only supported kinds are kept`() {
        val entities = listOf(
            entity("l1", "Lampe", Kind.LIGHT, "salon"),
            entity("s1", "Prise", Kind.SWITCH, "salon"),
            entity("c1", "Volet", Kind.COVER, "salon"),
            entity("a1", "Alarme", Kind.ALARM_PANEL, "salon"),
            entity("t1", "Température", Kind.SENSOR, "salon"),
            entity("b1", "Mouvement", Kind.BINARY_SENSOR, "salon"),
            entity("n1", "Luminosité", Kind.NUMBER, "salon"),
        )
        val catalog = VoiceCatalogBuilder.build(VoiceLang.FR, entities, areas)
        assertEquals(setOf("l1", "s1", "c1", "a1"), catalog.entities.map { it.uniqueId }.toSet())
    }

    @Test fun `hidden entities are dropped`() {
        val entities = listOf(entity("l1", "Lampe", Kind.LIGHT, "salon", hidden = true))
        val catalog = VoiceCatalogBuilder.build(VoiceLang.FR, entities, areas)
        assertTrue(catalog.entities.isEmpty())
    }

    @Test fun `entities with partOf are dropped`() {
        val entities = listOf(
            entity("cover", "Volet", Kind.COVER, "salon"),
            entity("moving", "Moving", Kind.BINARY_SENSOR, "salon", partOf = "cover"),
        )
        val catalog = VoiceCatalogBuilder.build(VoiceLang.FR, entities, areas)
        assertEquals(listOf("cover"), catalog.entities.map { it.uniqueId })
    }

    @Test fun `an override name wins over the entity name`() {
        val entities = listOf(
            entity("l1", "0x001", Kind.LIGHT, "salon", override = com.gekkotron.oikos.model.UiOverride(name = "Lampe salon")),
        )
        val catalog = VoiceCatalogBuilder.build(VoiceLang.FR, entities, areas)
        assertEquals("Lampe salon", catalog.entity("l1")!!.displayName)
    }

    @Test fun `an area whose name matches a room word is not a person room (FR)`() {
        val catalog = VoiceCatalogBuilder.build(VoiceLang.FR, emptyList(), areas)
        val salon = catalog.area("salon")!!
        assertFalse(salon.isPersonRoom)
        assertEquals("living", salon.roomConcept)
    }

    @Test fun `an area whose name is a person is marked as a person room`() {
        val catalog = VoiceCatalogBuilder.build(VoiceLang.FR, emptyList(), areas)
        val remi = catalog.area("remi")!!
        assertTrue(remi.isPersonRoom)
        assertNull(remi.roomConcept)
    }

    @Test fun `EN lexicon recognizes English room words`() {
        val englishAreas = listOf(area("living", "Living room"), area("kitchen", "Kitchen"))
        val catalog = VoiceCatalogBuilder.build(VoiceLang.EN, emptyList(), englishAreas)
        assertEquals("living", catalog.area("living")!!.roomConcept)
        assertEquals("kitchen", catalog.area("kitchen")!!.roomConcept)
    }

    @Test fun `the catalog preserves capability flags`() {
        val entities = listOf(entity("l1", "Lampe", Kind.LIGHT, "salon", brightness = true))
        val l1 = VoiceCatalogBuilder.build(VoiceLang.FR, entities, areas).entity("l1")!!
        assertTrue(l1.brightness)
        assertFalse(l1.position)
    }
}
