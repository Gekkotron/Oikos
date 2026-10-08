package com.gekkotron.oikos.feature.widget

import com.gekkotron.oikos.feature.widget.model.TileSnapshot
import com.gekkotron.oikos.feature.widget.model.TileStatus
import com.gekkotron.oikos.feature.widget.model.TileValue
import com.gekkotron.oikos.feature.widget.model.WidgetCommand
import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.CodeFormat
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Kind

sealed interface TileAction {
    data class Send(val id: String, val command: WidgetCommand) : TileAction
    data class AskCode(val id: String, val command: WidgetCommand, val format: CodeFormat) : TileAction
    data class OpenHistory(val id: String) : TileAction
    data object OpenHome : TileAction
}

/** What a tap does, per kind (widgets spec §2.3). Pure. */
object TileActions {
    fun forTap(tile: TileSnapshot, armMode: AlarmMode?): TileAction {
        if (!actionable(tile)) return TileAction.OpenHome
        val id = tile.uniqueId
        return when (tile.kind) {
            Kind.LIGHT, Kind.SWITCH -> TileAction.Send(id, WidgetCommand.Toggle)
            Kind.BUTTON -> TileAction.Send(id, WidgetCommand.Press)
            Kind.COVER -> {
                val closed = (tile.value as? TileValue.Cover)?.state == CoverState.CLOSED
                TileAction.Send(id, if (closed) WidgetCommand.Open else WidgetCommand.Close)
            }
            Kind.CLIMATE -> climate(tile)
            Kind.ALARM_PANEL -> alarm(tile, armMode)
            Kind.SENSOR, Kind.BINARY_SENSOR, Kind.NUMBER, Kind.SELECT ->
                if (tile.historyAvailable) TileAction.OpenHistory(id) else TileAction.OpenHome
        }
    }

    /** The Open / Stop / Close buttons of a wide cover tile. */
    fun coverButton(tile: TileSnapshot, command: WidgetCommand): TileAction =
        if (actionable(tile)) TileAction.Send(tile.uniqueId, command) else TileAction.OpenHome

    private fun actionable(tile: TileSnapshot) = tile.status != TileStatus.REMOVED && tile.available

    private fun climate(tile: TileSnapshot): TileAction {
        val onModes = tile.hvacModes.filter { it != "off" }
        if (onModes.isEmpty()) return TileAction.OpenHome
        val mode = (tile.value as? TileValue.Climate)?.mode
        if (mode != null && mode != "off") return TileAction.Send(tile.uniqueId, WidgetCommand.Hvac("off"))
        return TileAction.Send(tile.uniqueId, WidgetCommand.Hvac(tile.lastOnMode ?: onModes.first()))
    }

    private fun alarm(tile: TileSnapshot, armMode: AlarmMode?): TileAction {
        val state = (tile.value as? TileValue.Alarm)?.state ?: return TileAction.OpenHome
        val command: WidgetCommand
        val needsCode: Boolean
        if (state == AlarmState.DISARMED) {
            val mode = armMode?.takeIf { it in tile.alarmModes }
                ?: AlarmMode.AWAY.takeIf { it in tile.alarmModes }
                ?: tile.alarmModes.firstOrNull()
                ?: return TileAction.OpenHome
            command = WidgetCommand.Arm(mode)
            needsCode = tile.codeArmRequired
        } else {
            command = WidgetCommand.Disarm
            needsCode = tile.codeDisarmRequired
        }
        return if (needsCode) TileAction.AskCode(tile.uniqueId, command, tile.codeFormat) else TileAction.Send(tile.uniqueId, command)
    }
}
