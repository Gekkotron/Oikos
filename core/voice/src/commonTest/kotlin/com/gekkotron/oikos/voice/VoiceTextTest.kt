package com.gekkotron.oikos.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceTextTest {
    @Test fun `prepare folds accents and lowercases`() {
        assertEquals("ouvre les volets du salon", VoiceText.prepare("Ouvre les volets du Salon.", VoiceLang.FR))
    }

    @Test fun `prepare rewrites multi-word FR phrases`() {
        assertEquals("allume la bathroom", VoiceText.prepare("allume la salle de bain", VoiceLang.FR))
    }

    @Test fun `prepare rewrites multi-word EN phrases`() {
        assertEquals("open the living shutter", VoiceText.prepare("open the living room shutter", VoiceLang.EN))
    }

    @Test fun `normalizeShortcut drops FR filler words`() {
        assertEquals("ouvre volets salon", VoiceText.normalizeShortcut("Ouvre les volets du salon", VoiceLang.FR))
    }

    @Test fun `normalizeShortcut drops EN filler words`() {
        assertEquals("open shutters living", VoiceText.normalizeShortcut("Open the shutters in the living room", VoiceLang.EN))
    }
}
