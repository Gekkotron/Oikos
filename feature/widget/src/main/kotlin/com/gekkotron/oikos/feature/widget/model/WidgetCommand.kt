package com.gekkotron.oikos.feature.widget.model

import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.Command
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The commands a widget can send; serialisable so a worker's input can carry one. Never holds a code. */
@Serializable
sealed interface WidgetCommand {
    @Serializable @SerialName("toggle")
    data object Toggle : WidgetCommand

    @Serializable @SerialName("open")
    data object Open : WidgetCommand

    @Serializable @SerialName("close")
    data object Close : WidgetCommand

    @Serializable @SerialName("stop")
    data object Stop : WidgetCommand

    @Serializable @SerialName("press")
    data object Press : WidgetCommand

    @Serializable @SerialName("hvac")
    data class Hvac(val mode: String) : WidgetCommand

    @Serializable @SerialName("arm")
    data class Arm(val mode: AlarmMode) : WidgetCommand

    @Serializable @SerialName("disarm")
    data object Disarm : WidgetCommand

    fun toCommand(code: String? = null): Command = when (this) {
        Toggle -> Command.Toggle
        Open -> Command.Open
        Close -> Command.Close
        Stop -> Command.Stop
        Press -> Command.Press
        is Hvac -> Command.SetHvacMode(mode)
        is Arm -> Command.Arm(mode, code)
        Disarm -> Command.Disarm(code)
    }

    fun encode(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun decode(text: String): WidgetCommand? = runCatching { Json.decodeFromString(serializer(), text) }.getOrNull()
    }
}
