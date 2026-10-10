package com.gekkotron.oikos.voice

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
