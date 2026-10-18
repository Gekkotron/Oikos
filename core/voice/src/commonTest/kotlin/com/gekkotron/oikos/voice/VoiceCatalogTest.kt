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
            entity("x1", "Autre", Kind.SELECT, "salon"),
        )
        val catalog = VoiceCatalogBuilder.build(VoiceLang.FR, entities, areas)
        assertEquals(setOf("l1", "s1", "c1", "a1"), catalog.entities.map { it.uniqueId }.toSet())
    }

    @Test fun `VoiceCatalogBuilder keeps SENSOR, BINARY_SENSOR, CLIMATE, VACUUM, WATER_HEATER, NUMBER`() {
        val kinds = listOf(Kind.SENSOR, Kind.BINARY_SENSOR, Kind.CLIMATE, Kind.VACUUM, Kind.WATER_HEATER, Kind.NUMBER)
        val entities = kinds.mapIndexed { i, k -> entity("e$i", "E$i", k, "salon") }
        val catalog = VoiceCatalogBuilder.build(VoiceLang.FR, entities, areas)
        assertEquals(entities.map { it.uniqueId }.toSet(), catalog.entities.map { it.uniqueId }.toSet())
    }

    @Test fun `VoiceEntity carries Entity unit`() {
        val entities = listOf(entity("t1", "Température", Kind.SENSOR, "salon", unit = "°C"))
        val t1 = VoiceCatalogBuilder.build(VoiceLang.FR, entities, areas).entity("t1")!!
        assertEquals("°C", t1.unit)
    }

    @Test fun `partOf and hidden entities are still dropped from the new kinds`() {
        val entities = listOf(
            entity("t1", "Temp", Kind.SENSOR, "salon", hidden = true),
            entity("t2", "Temp2", Kind.SENSOR, "salon", partOf = "c1"),
            entity("t3", "Temp3", Kind.SENSOR, "salon"),
        )
        val catalog = VoiceCatalogBuilder.build(VoiceLang.FR, entities, areas)
        assertEquals(listOf("t3"), catalog.entities.map { it.uniqueId })
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
