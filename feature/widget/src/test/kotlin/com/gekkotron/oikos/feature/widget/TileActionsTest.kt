package com.gekkotron.oikos.feature.widget

import com.gekkotron.oikos.feature.widget.model.TileIcon
import com.gekkotron.oikos.feature.widget.model.TileSnapshot
import com.gekkotron.oikos.feature.widget.model.TileStatus
import com.gekkotron.oikos.feature.widget.model.TileValue
import com.gekkotron.oikos.feature.widget.model.WidgetCommand
import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.CodeFormat
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class TileActionsTest {
    private fun tile(kind: Kind, value: TileValue, f: (TileSnapshot) -> TileSnapshot = { it }) =
        f(TileSnapshot("x", "X", kind, TileIcon.SENSOR, value))

    @Test
    fun switch_toggles() =
        assertEquals(TileAction.Send("x", WidgetCommand.Toggle), TileActions.forTap(tile(Kind.SWITCH, TileValue.OnOff(true)), null))

    @Test
    fun button_presses() =
        assertEquals(TileAction.Send("x", WidgetCommand.Press), TileActions.forTap(tile(Kind.BUTTON, TileValue.None), null))

    @Test
    fun closed_cover_opens_open_cover_closes() {
        assertEquals(TileAction.Send("x", WidgetCommand.Open), TileActions.forTap(tile(Kind.COVER, TileValue.Cover(CoverState.CLOSED, 0)), null))
        assertEquals(TileAction.Send("x", WidgetCommand.Close), TileActions.forTap(tile(Kind.COVER, TileValue.Cover(CoverState.OPEN, 100)), null))
    }

    @Test
    fun climate_off_turns_on_with_last_mode_else_first_non_off() {
        val off = tile(Kind.CLIMATE, TileValue.Climate("off", null, null)) { it.copy(hvacModes = listOf("off", "heat"), lastOnMode = "cool") }
        assertEquals(TileAction.Send("x", WidgetCommand.Hvac("cool")), TileActions.forTap(off, null))
        assertEquals(TileAction.Send("x", WidgetCommand.Hvac("heat")), TileActions.forTap(off.copy(lastOnMode = null), null))
        val on = off.copy(value = TileValue.Climate("heat", null, null))
        assertEquals(TileAction.Send("x", WidgetCommand.Hvac("off")), TileActions.forTap(on, null))
    }

    @Test
    fun climate_without_modes_opens_home() {
        assertEquals(TileAction.OpenHome, TileActions.forTap(tile(Kind.CLIMATE, TileValue.Climate("off", null, null)), null))
    }

    @Test
    fun disarmed_alarm_arms_with_configured_mode_or_away_or_first() {
        val a = tile(Kind.ALARM_PANEL, TileValue.Alarm(AlarmState.DISARMED)) { it.copy(alarmModes = setOf(AlarmMode.HOME, AlarmMode.AWAY)) }
        assertEquals(TileAction.Send("x", WidgetCommand.Arm(AlarmMode.HOME)), TileActions.forTap(a, AlarmMode.HOME))
        assertEquals(TileAction.Send("x", WidgetCommand.Arm(AlarmMode.AWAY)), TileActions.forTap(a, null))
        assertEquals(TileAction.Send("x", WidgetCommand.Arm(AlarmMode.HOME)), TileActions.forTap(a.copy(alarmModes = setOf(AlarmMode.HOME)), AlarmMode.NIGHT))
    }

    @Test
    fun armed_alarm_needing_code_asks_for_it() {
        val a = tile(Kind.ALARM_PANEL, TileValue.Alarm(AlarmState.ARMED_AWAY)) { it.copy(codeDisarmRequired = true, codeFormat = CodeFormat.TEXT) }
        assertEquals(TileAction.AskCode("x", WidgetCommand.Disarm, CodeFormat.TEXT), TileActions.forTap(a, null))
    }

    @Test
    fun arming_that_needs_a_code_asks_for_it() {
        val a = tile(Kind.ALARM_PANEL, TileValue.Alarm(AlarmState.DISARMED)) { it.copy(alarmModes = setOf(AlarmMode.AWAY), codeArmRequired = true) }
        assertEquals(TileAction.AskCode("x", WidgetCommand.Arm(AlarmMode.AWAY), CodeFormat.NUMBER), TileActions.forTap(a, null))
    }

    @Test
    fun alarm_with_unknown_state_opens_home() {
        assertEquals(TileAction.OpenHome, TileActions.forTap(tile(Kind.ALARM_PANEL, TileValue.None), null))
    }

    @Test
    fun sensors_open_history_when_available_else_home() {
        val s = tile(Kind.SENSOR, TileValue.Measure(1.0, "W"))
        assertEquals(TileAction.OpenHome, TileActions.forTap(s, null))
        assertEquals(TileAction.OpenHistory("x"), TileActions.forTap(s.copy(historyAvailable = true), null))
    }

    @Test
    fun removed_or_unavailable_tiles_open_home() {
        assertEquals(TileAction.OpenHome, TileActions.forTap(tile(Kind.SWITCH, TileValue.OnOff(true)) { it.copy(status = TileStatus.REMOVED) }, null))
        assertEquals(TileAction.OpenHome, TileActions.forTap(tile(Kind.SWITCH, TileValue.OnOff(true)) { it.copy(available = false) }, null))
    }

    @Test
    fun cover_buttons() {
        val c = tile(Kind.COVER, TileValue.Cover(CoverState.OPEN, 100))
        assertEquals(TileAction.Send("x", WidgetCommand.Stop), TileActions.coverButton(c, WidgetCommand.Stop))
    }

    @Test
    fun widget_command_round_trips_and_carries_the_code_only_into_the_command() {
        val encoded = WidgetCommand.Arm(AlarmMode.NIGHT).encode()
        assertEquals(WidgetCommand.Arm(AlarmMode.NIGHT), WidgetCommand.decode(encoded))
        assertEquals(Command.Disarm("1234"), WidgetCommand.Disarm.toCommand("1234"))
        assertEquals(null, WidgetCommand.decode("{garbage"))
    }
}
