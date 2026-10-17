package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Kind
import kotlin.math.roundToInt

object CommandMapper {
    fun map(intent: VoiceIntent, catalog: VoiceCatalog, code: String? = null): List<Pair<String, Command>> =
        intent.entityIds.mapNotNull { id -> catalog.entity(id)?.let { e -> commandFor(intent, e, code)?.let { id to it } } }

    private fun commandFor(intent: VoiceIntent, e: VoiceEntity, code: String?): Command? = when (intent.action) {
        VoiceAction.TURN_ON -> if (e.kind == Kind.LIGHT || e.kind == Kind.SWITCH) Command.TurnOn() else null
        VoiceAction.TURN_OFF -> if (e.kind == Kind.LIGHT || e.kind == Kind.SWITCH) Command.TurnOff else null
        VoiceAction.OPEN -> if (e.kind == Kind.COVER) Command.Open else null
        VoiceAction.CLOSE -> if (e.kind == Kind.COVER) Command.Close else null
        VoiceAction.STOP -> if (e.kind == Kind.COVER) Command.Stop else null
        VoiceAction.SET_POSITION -> if (e.kind == Kind.COVER && e.position && intent.value != null) Command.SetPosition(intent.value) else null
        VoiceAction.SET_BRIGHTNESS -> if (e.kind == Kind.LIGHT && e.brightness && intent.value != null) Command.TurnOn(brightness = (intent.value * 255 / 100.0).roundToInt().coerceIn(0, 255)) else null
        VoiceAction.ARM_AWAY -> if (e.kind == Kind.ALARM_PANEL) Command.Arm(AlarmMode.AWAY, code) else null
        VoiceAction.ARM_HOME -> if (e.kind == Kind.ALARM_PANEL) Command.Arm(AlarmMode.HOME, code) else null
        VoiceAction.DISARM -> if (e.kind == Kind.ALARM_PANEL) Command.Disarm(code) else null
        VoiceAction.QUERY_STATE -> null
    }
}
