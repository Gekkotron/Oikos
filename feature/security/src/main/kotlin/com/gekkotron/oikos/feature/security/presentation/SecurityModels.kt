package com.gekkotron.oikos.feature.security.presentation

import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.SensorGroup
import com.gekkotron.oikos.model.SwitchType
import com.gekkotron.oikos.transport.LinkState
import java.time.Duration
import java.time.Instant

/** [state] null: nothing received yet. */
data class PanelUi(
    val id: String,
    val name: String,
    val state: AlarmState?,
    val available: Boolean,
    val pending: Boolean,
    val stale: Boolean,
    val capabilities: Capabilities,
)

/** [active] null: no state yet. [areaName] null: Unassigned (localized by the UI). */
data class SensorRow(
    val id: String,
    val name: String,
    val areaName: String?,
    val deviceClass: String?,
    val group: SensorGroup,
    val active: Boolean?,
    val available: Boolean,
    val stale: Boolean = false,
    /** How long the sensor has been in its state (last-change spec §4.4); null: unknown. */
    val since: Since? = null,
)

/** [At]: the state began then. [Over]: recorded, but unchanged for longer than [Over.window]. */
sealed interface Since {
    data class At(val at: Instant) : Since
    data class Over(val window: Duration) : Since
}

data class GroupCount(val group: SensorGroup, val count: Int)

/** The tab's sensor sections; the enum order is the screen order (openings and motion on top). */
enum class SensorSection {
    OPENINGS, MOTION, FIRE, WATER, OTHER,
    ;

    companion object {
        fun of(group: SensorGroup): SensorSection = when (group) {
            SensorGroup.SMOKE, SensorGroup.CO, SensorGroup.GAS -> FIRE
            SensorGroup.LEAK -> WATER
            SensorGroup.OPENING -> OPENINGS
            SensorGroup.MOTION -> MOTION
            SensorGroup.SOS, SensorGroup.VIBRATION, SensorGroup.TAMPER -> OTHER
        }
    }
}

/** [rows] stay in stable alphabetical order (area then name) so a tile does not jump when its state flips; a section is never empty. */
data class SectionUi(val section: SensorSection, val rows: List<SensorRow>)

/** A switch or button opted onto Security (security actions spec §3.1). [on] null: a button, or no state yet. [areaName] null: Unassigned. */
data class ActionRow(
    val id: String,
    val name: String,
    val areaName: String?,
    val kind: Kind,
    val on: Boolean?,
    val available: Boolean,
    val stale: Boolean,
    val ask: Boolean,
    /** The switch's presentation type (device sheet spec §5.3); null for a Button. */
    val switchType: SwitchType? = null,
)

/** What a tap on an action does, and whether it asks first (security actions spec §3.3). */
data class ActionTap(val command: Command, val confirm: Boolean)

fun ActionRow.tap(): ActionTap = when {
    kind == Kind.BUTTON -> ActionTap(Command.Press, confirm = ask)
    switchType == SwitchType.DOOR -> ActionTap(Command.TurnOn(), confirm = ask)
    switchType == SwitchType.SIREN && on == true -> ActionTap(Command.TurnOff, confirm = false)
    switchType == SwitchType.SIREN -> ActionTap(Command.TurnOn(), confirm = ask)
    switchType == SwitchType.CHIME -> ActionTap(Command.TurnOn(), confirm = false)
    on == true -> ActionTap(Command.TurnOff, confirm = false)
    else -> ActionTap(Command.TurnOn(), confirm = ask)
}

/** [loading] is true only before the first build, so the empty state never flashes. */
data class SecurityUi(
    val link: LinkState = LinkState.Connecting,
    val panels: List<PanelUi> = emptyList(),
    val summary: List<GroupCount> = emptyList(),
    val sensors: List<SensorRow> = emptyList(),
    val sections: List<SectionUi> = emptyList(),
    val actions: List<ActionRow> = emptyList(),
    val loading: Boolean = true,
) {
    /** Nothing at all to show: one empty state instead of the sections. */
    val isEmpty: Boolean get() = panels.isEmpty() && sensors.isEmpty() && actions.isEmpty()

    fun panel(id: String): PanelUi? = panels.firstOrNull { it.id == id }

    fun action(id: String): ActionRow? = actions.firstOrNull { it.id == id }
}

sealed interface SecurityEvent {
    data class NotConfirmed(val id: String, val command: Command) : SecurityEvent
    data class Rejected(val reason: String) : SecurityEvent
    data object SaveFailed : SecurityEvent
    data object WaitingForConnection : SecurityEvent
}
