package com.gekkotron.oikos.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceLexiconTest {
    @Test fun `fromAppLocale returns FR for fr-FR and fr`() {
        assertEquals(VoiceLang.FR, VoiceLang.fromAppLocale("fr-FR"))
        assertEquals(VoiceLang.FR, VoiceLang.fromAppLocale("fr"))
    }

    @Test fun `fromAppLocale returns EN for anything else`() {
        assertEquals(VoiceLang.EN, VoiceLang.fromAppLocale("en-US"))
        assertEquals(VoiceLang.EN, VoiceLang.fromAppLocale("de-DE"))
        assertEquals(VoiceLang.EN, VoiceLang.fromAppLocale(""))
    }

    @Test fun `fr table has French verbs mapped to the right concepts`() {
        val t = VoiceLexicon.of(VoiceLang.FR)
        assertEquals(VoiceLexicon.Verb.ON, t.verbs["allume"])
        assertEquals(VoiceLexicon.Verb.OFF, t.verbs["eteins"])
        assertEquals(VoiceLexicon.Verb.OPEN, t.verbs["ouvre"])
        assertEquals(VoiceLexicon.Verb.CLOSE, t.verbs["ferme"])
        assertEquals(VoiceLexicon.Verb.ARM, t.verbs["arme"])
        assertEquals(VoiceLexicon.Verb.DISARM, t.verbs["desarme"])
    }

    @Test fun `en table has English verbs mapped to the right concepts`() {
        val t = VoiceLexicon.of(VoiceLang.EN)
        assertEquals(VoiceLexicon.Verb.ON, t.verbs["turn"])
        assertEquals(VoiceLexicon.Verb.ON, t.verbs["switch"])
        assertEquals(VoiceLexicon.Verb.OPEN, t.verbs["open"])
        assertEquals(VoiceLexicon.Verb.CLOSE, t.verbs["close"])
        assertEquals(VoiceLexicon.Verb.ARM, t.verbs["arm"])
        assertEquals(VoiceLexicon.Verb.DISARM, t.verbs["disarm"])
    }

    @Test fun `both tables share the same canonical device concepts`() {
        val fr = VoiceLexicon.of(VoiceLang.FR).devices
        val en = VoiceLexicon.of(VoiceLang.EN).devices
        assertEquals("cover", fr["volet"])
        assertEquals("cover", en["shutter"])
        assertEquals("light", fr["lumiere"])
        assertEquals("light", en["light"])
        assertEquals("siren", fr["sirene"])
        assertEquals("siren", en["siren"])
    }

    @Test fun `both tables share the same canonical room concepts`() {
        val fr = VoiceLexicon.of(VoiceLang.FR).rooms
        val en = VoiceLexicon.of(VoiceLang.EN).rooms
        assertEquals("living", fr["salon"])
        assertEquals("living", en["living"])
        assertEquals("kitchen", fr["cuisine"])
        assertEquals("kitchen", en["kitchen"])
        assertEquals("bedroom", fr["chambre"])
        assertEquals("bedroom", en["bedroom"])
    }

    @Test fun `French action labels are translated`() {
        assertEquals("Allumer", VoiceLexicon.actionLabel(VoiceLang.FR, VoiceAction.TURN_ON))
        assertEquals("Désarmer", VoiceLexicon.actionLabel(VoiceLang.FR, VoiceAction.DISARM))
    }

    @Test fun `English action labels are translated`() {
        assertEquals("Turn on", VoiceLexicon.actionLabel(VoiceLang.EN, VoiceAction.TURN_ON))
        assertEquals("Disarm", VoiceLexicon.actionLabel(VoiceLang.EN, VoiceAction.DISARM))
    }

    @Test fun `noise contains filler words for its language`() {
        assertTrue("les" in VoiceLexicon.of(VoiceLang.FR).noise)
        assertTrue("the" in VoiceLexicon.of(VoiceLang.EN).noise)
    }

    @Test fun `FR attributes map temperature humidite batterie puissance consommation tension courant luminosite pression niveau etat`() {
        val a = VoiceLexicon.of(VoiceLang.FR).attributes
        assertEquals("temperature", a["temperature"])
        assertEquals("temperature", a["temperatures"])
        assertEquals("humidity", a["humidite"])
        assertEquals("battery", a["batterie"])
        assertEquals("power", a["puissance"])
        assertEquals("energy", a["consommation"])
        assertEquals("voltage", a["tension"])
        assertEquals("current", a["courant"])
        assertEquals("illuminance", a["luminosite"])
        assertEquals("illuminance", a["lux"])
        assertEquals("pressure", a["pression"])
        assertEquals("level", a["niveau"])
        assertEquals("state", a["etat"])
        assertEquals("state", a["statut"])
    }

    @Test fun `EN attributes map temperature humidity battery power energy voltage current illuminance pressure level state`() {
        val a = VoiceLexicon.of(VoiceLang.EN).attributes
        assertEquals("temperature", a["temperature"])
        assertEquals("humidity", a["humidity"])
        assertEquals("battery", a["battery"])
        assertEquals("power", a["power"])
        assertEquals("energy", a["energy"])
        assertEquals("voltage", a["voltage"])
        assertEquals("current", a["current"])
        assertEquals("illuminance", a["illuminance"])
        assertEquals("illuminance", a["lux"])
        assertEquals("pressure", a["pressure"])
        assertEquals("level", a["level"])
        assertEquals("state", a["state"])
        assertEquals("state", a["status"])
    }

    @Test fun `FR verbs contain QUERY entries`() {
        val v = VoiceLexicon.of(VoiceLang.FR).verbs
        listOf("quelle", "quel", "quels", "quelles", "combien", "affiche", "dis", "dites", "donne", "lit", "lis", "lire", "montre", "verifie")
            .forEach { assertEquals(VoiceLexicon.Verb.QUERY, v[it], it) }
    }

    @Test fun `EN verbs contain QUERY entries`() {
        val v = VoiceLexicon.of(VoiceLang.EN).verbs
        listOf("what", "how", "show", "tell", "read", "give", "check")
            .forEach { assertEquals(VoiceLexicon.Verb.QUERY, v[it], it) }
    }

    private fun structural(lang: VoiceLang, text: String): Boolean {
        val prepared = VoiceText.prepare(text, lang)
        return VoiceLexicon.of(lang).structuralQuery.any { it.containsMatchIn(prepared) }
    }

    @Test fun `FR structural phrases match est-ce que, qu'est-ce que`() {
        assertTrue(structural(VoiceLang.FR, "Est-ce que les volets sont fermés ?"))
        assertTrue(structural(VoiceLang.FR, "est ce qu'il fait chaud"))
        assertTrue(structural(VoiceLang.FR, "Qu'est-ce que tu fais"))
        assertTrue(structural(VoiceLang.FR, "qu'est ce que la lumière fait"))
        assertFalse(structural(VoiceLang.FR, "ferme les volets"))
    }

    @Test fun `EN structural phrases match is it, are the, what is the`() {
        assertTrue(structural(VoiceLang.EN, "Is it dark in the kitchen"))
        assertTrue(structural(VoiceLang.EN, "are the shutters closed"))
        assertTrue(structural(VoiceLang.EN, "are there lights on"))
        assertTrue(structural(VoiceLang.EN, "What is the temperature"))
        assertTrue(structural(VoiceLang.EN, "what are the lights doing"))
        assertFalse(structural(VoiceLang.EN, "close the shutters"))
    }

    @Test fun `QUERY verb is reachable independently of structural phrases`() {
        val fr = VoiceLexicon.of(VoiceLang.FR)
        val prepared = VoiceText.prepare("Quelle est la température du salon", VoiceLang.FR)
        assertFalse(fr.structuralQuery.any { it.containsMatchIn(prepared) })
        assertTrue(VoiceText.words(prepared).any { fr.verbs[it] == VoiceLexicon.Verb.QUERY })
    }

    @Test fun `kind labels are translated`() {
        assertEquals("Lumière", VoiceLexicon.kindLabel(VoiceLang.FR, com.gekkotron.oikos.model.Kind.LIGHT))
        assertEquals("Capteur", VoiceLexicon.kindLabel(VoiceLang.FR, com.gekkotron.oikos.model.Kind.BINARY_SENSOR))
        assertEquals("Water heater", VoiceLexicon.kindLabel(VoiceLang.EN, com.gekkotron.oikos.model.Kind.WATER_HEATER))
        assertEquals("Shutter", VoiceLexicon.kindLabel(VoiceLang.EN, com.gekkotron.oikos.model.Kind.COVER))
    }
}
