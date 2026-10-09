package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandMapperTest {
    private val catalog = VoiceCatalogBuilder.build(
        lang = VoiceLang.FR,
        entities = listOf(
            entity("light", "Lampe", Kind.LIGHT, "salon", brightness = true),
            entity("switch", "Prise", Kind.SWITCH, "salon"),
            entity("cover_pos", "Volet 1", Kind.COVER, "salon", position = true),
            entity("cover_nopos", "Volet 2", Kind.COVER, "salon", position = false),
            entity("alarm", "Alarme", Kind.ALARM_PANEL, "entrance"),
        ),
        areas = listOf(area("salon", "Salon"), area("entrance", "Entrée")),
    )

    @Test fun `TURN_ON maps light and switch to TurnOn`() {
        val commands = CommandMapper.map(VoiceIntent(VoiceAction.TURN_ON, listOf("light", "switch")), catalog)
        assertEquals(2, commands.size)
        assertTrue(commands.all { it.second is Command.TurnOn })
    }

    @Test fun `SET_BRIGHTNESS scales 0-100 into 0-255 rounded`() {
        val commands = CommandMapper.map(VoiceIntent(VoiceAction.SET_BRIGHTNESS, listOf("light"), value = 50), catalog)
        assertEquals(listOf("light" to Command.TurnOn(brightness = 128)), commands)
    }

    @Test fun `SET_POSITION only hits covers with position`() {
        val commands = CommandMapper.map(VoiceIntent(VoiceAction.SET_POSITION, listOf("cover_pos", "cover_nopos"), value = 30), catalog)
        assertEquals(listOf("cover_pos" to Command.SetPosition(30)), commands)
    }

    @Test fun `incompatible kind-command pairs are dropped and an intent with none left is rejected`() {
        // CLOSE on a LIGHT would be bogus — the LIGHT is dropped.
        val mixed = CommandMapper.map(VoiceIntent(VoiceAction.CLOSE, listOf("light", "cover_pos")), catalog)
        assertEquals(listOf("cover_pos" to Command.Close), mixed)

        // CLOSE on only a LIGHT yields nothing.
        val empty = CommandMapper.map(VoiceIntent(VoiceAction.CLOSE, listOf("light")), catalog)
        assertTrue(empty.isEmpty())
    }

    @Test fun `ARM_AWAY produces Arm AWAY on the panel with the code`() {
        val commands = CommandMapper.map(VoiceIntent(VoiceAction.ARM_AWAY, listOf("alarm")), catalog, code = "1234")
        assertEquals(listOf("alarm" to Command.Arm(AlarmMode.AWAY, code = "1234")), commands)
    }

    @Test fun `DISARM produces Disarm with the code`() {
        val commands = CommandMapper.map(VoiceIntent(VoiceAction.DISARM, listOf("alarm")), catalog, code = "9999")
        assertEquals(listOf("alarm" to Command.Disarm(code = "9999")), commands)
    }

    @Test fun `an entity id missing from the catalog is skipped`() {
        val commands = CommandMapper.map(VoiceIntent(VoiceAction.OPEN, listOf("cover_pos", "ghost")), catalog)
        assertEquals(listOf("cover_pos" to Command.Open), commands)
    }
}
