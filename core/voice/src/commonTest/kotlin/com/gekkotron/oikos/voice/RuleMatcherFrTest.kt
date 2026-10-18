package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RuleMatcherFrTest {
    private val catalog = VoiceCatalogBuilder.build(
        lang = VoiceLang.FR,
        entities = listOf(
            entity("volet_salon_1", "Volet salon 1", Kind.COVER, "salon", position = true),
            entity("volet_salon_2", "Volet salon 2", Kind.COVER, "salon", position = true),
            entity("volet_cuisine", "Volet cuisine", Kind.COVER, "kitchen", position = true),
            entity("lampe_salon", "Lampe salon", Kind.LIGHT, "salon", brightness = true),
            entity("lampe_bureau", "Lampe bureau", Kind.LIGHT, "office", brightness = true),
            entity("ampoule_salon_sw", "Ampoule salon", Kind.SWITCH, "salon"),
            entity("prise_cafe", "Prise café", Kind.SWITCH, "kitchen"),
            entity("alarme", "Alarme", Kind.ALARM_PANEL, "entrance"),
        ),
        areas = listOf(area("salon", "Salon"), area("kitchen", "Cuisine"), area("office", "Bureau"), area("entrance", "Entrée")),
    )
    private val matcher = RuleMatcher(catalog)

    @Test fun `opens every shutter of a room when a room is said`() {
        val intent = matcher.resolve("ouvre les volets du salon")
        assertNotNull(intent)
        assertEquals(VoiceAction.OPEN, intent.action)
        assertEquals(setOf("volet_salon_1", "volet_salon_2"), intent.entityIds.toSet())
    }

    @Test fun `singular light plus room means the whole light group of the room`() {
        val intent = matcher.resolve("allume la lumière du salon")
        assertNotNull(intent)
        assertEquals(VoiceAction.TURN_ON, intent.action)
        // Includes the SWITCH named like a light (ampoule_salon_sw) alongside the LIGHT.
        assertEquals(setOf("lampe_salon", "ampoule_salon_sw"), intent.entityIds.toSet())
    }

    @Test fun `naming the device still picks only it`() {
        val intent = matcher.resolve("éteins la lampe du salon")
        assertNotNull(intent)
        assertEquals(VoiceAction.TURN_OFF, intent.action)
        assertEquals(listOf("lampe_salon"), intent.entityIds)
    }

    @Test fun `set position carries the value and only hits positionable members`() {
        val intent = matcher.resolve("mets les volets du salon à 30 %")
        assertNotNull(intent)
        assertEquals(VoiceAction.SET_POSITION, intent.action)
        assertEquals(30, intent.value)
        assertEquals(setOf("volet_salon_1", "volet_salon_2"), intent.entityIds.toSet())
    }

    @Test fun `set brightness carries the value as 0-100`() {
        val intent = matcher.resolve("mets la lampe du salon à 50 %")
        assertNotNull(intent)
        assertEquals(VoiceAction.SET_BRIGHTNESS, intent.action)
        assertEquals(50, intent.value)
    }

    @Test fun `arm away by default`() {
        val intent = matcher.resolve("arme l'alarme")
        assertNotNull(intent)
        assertEquals(VoiceAction.ARM_AWAY, intent.action)
        assertEquals(listOf("alarme"), intent.entityIds)
    }

    @Test fun `arm home when a home-mode word is said`() {
        val intent = matcher.resolve("arme l'alarme en mode nuit")
        assertEquals(VoiceAction.ARM_HOME, intent?.action)
    }

    @Test fun `an ambiguous phrase returns null`() {
        assertNull(matcher.resolve("fais quelque chose"))
    }

    @Test fun `a bad value like 150 percent returns null`() {
        assertNull(matcher.resolve("mets les volets du salon à 150 %"))
    }

    @Test fun `naming a non-existent room returns null`() {
        assertNull(matcher.resolve("ouvre les volets de la cave"))
    }

    @Test fun `tolerates a one-letter Levenshtein slip on long words`() {
        // "volats" → "volets" (1 edit, both >=5 chars)
        val intent = matcher.resolve("ouvre les volats du salon")
        assertNotNull(intent)
        assertEquals(VoiceAction.OPEN, intent.action)
    }
}
