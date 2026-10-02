package com.gekkotron.oikos.feature.security.presentation

import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.SensorGroup
import com.gekkotron.oikos.transport.LinkState

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
)

data class GroupCount(val group: SensorGroup, val count: Int)

/** [loading] is true only before the first build, so the empty state never flashes. */
data class SecurityUi(
    val link: LinkState = LinkState.Connecting,
    val panels: List<PanelUi> = emptyList(),
    val summary: List<GroupCount> = emptyList(),
    val sensors: List<SensorRow> = emptyList(),
    val loading: Boolean = true,
) {
    fun panel(id: String): PanelUi? = panels.firstOrNull { it.id == id }
}

sealed interface SecurityEvent {
    data class NotConfirmed(val id: String, val command: Command) : SecurityEvent
    data class Rejected(val reason: String) : SecurityEvent
    data object SaveFailed : SecurityEvent
    data object WaitingForConnection : SecurityEvent
}
