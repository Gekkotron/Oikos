package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.Kind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfirmationTest {
    private val catalog = VoiceCatalogBuilder.build(
        lang = VoiceLang.FR,
        entities = listOf(
            entity("alarm", "Alarme", Kind.ALARM_PANEL, "entrance"),
            entity("plug", "Prise", Kind.SWITCH, "salon"),
            entity("siren_sw", "Sirène extérieure", Kind.SWITCH, "outdoor"),
            entity("siren_dc", "Buzzer", Kind.SWITCH, "outdoor", deviceClass = "siren"),
        ),
        areas = listOf(area("entrance", "Entrée"), area("salon", "Salon"), area("outdoor", "Dehors")),
    )

    @Test fun `any alarm action always confirms`() {
        for (action in listOf(VoiceAction.ARM_AWAY, VoiceAction.ARM_HOME, VoiceAction.DISARM)) {
            assertTrue(Confirmation.needsConfirmation(VoiceIntent(action, listOf("alarm")), catalog))
        }
    }

    @Test fun `needsConfirmation is true for a switch whose name is siren`() {
        assertTrue(Confirmation.needsConfirmation(VoiceIntent(VoiceAction.TURN_ON, listOf("siren_sw")), catalog))
    }

    @Test fun `needsConfirmation is true for a switch whose device_class is siren`() {
        assertTrue(Confirmation.needsConfirmation(VoiceIntent(VoiceAction.TURN_ON, listOf("siren_dc")), catalog))
    }

    @Test fun `a plain plug never confirms`() {
        assertFalse(Confirmation.needsConfirmation(VoiceIntent(VoiceAction.TURN_ON, listOf("plug")), catalog))
    }
}
