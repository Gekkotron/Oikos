package com.gekkotron.oikos.feature.security.presentation

import com.gekkotron.oikos.history.LastChange
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.Placement
import com.gekkotron.oikos.model.SensorGroup
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.SwitchType
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.transport.LinkState
import java.time.Instant

/** Entities → the Security tab (security spec §4.3). Pure. */
object SecurityUiBuilder {
    fun build(
        entities: Map<String, Entity>,
        states: Map<String, EntityState>,
        areas: List<Area>,
        link: LinkState,
        changes: Map<String, LastChange> = emptyMap(),
    ): SecurityUi {
        val areaNames = areas.associate { it.id to it.name }
        // Hidden on Home still shows here: only securityHidden takes an entity off Security.
        val visible = entities.values.filter { it.partOf == null && Placement.onSecurity(it.kind, it.deviceClass, it.ui) }
        val panels = visible.filter { it.kind == Kind.ALARM_PANEL }
            .sortedWith(compareBy<Entity>({ it.ui.order ?: Int.MAX_VALUE }, { it.name.lowercase() }, { it.uniqueId }))
            .map { e ->
                val s = states[e.uniqueId]
                PanelUi(
                    e.uniqueId, e.name, (s?.value as? StateValue.Alarm)?.state,
                    available = s?.available ?: true, pending = s?.pending == true, stale = s?.stale == true, capabilities = e.capabilities,
                )
            }
        val sensors = visible.filter { it.kind == Kind.BINARY_SENSOR }
            .mapNotNull { e ->
                val group = SensorGroup.of(e.deviceClass) ?: return@mapNotNull null
                val s = states[e.uniqueId]
                SensorRow(
                    e.uniqueId, e.name,
                    areaName = if (e.area == UNASSIGNED_AREA) null else areaNames[e.area] ?: e.area,
                    deviceClass = e.deviceClass, group = group,
                    active = (s?.value as? StateValue.OnOff)?.on,
                    available = s?.available ?: true,
                    stale = s?.stale == true,
                    since = since(changes[e.uniqueId], s?.changedAt),
                )
            }
            // Stable order: a tile does not move when its state flips (security spec §4.3). Area then name, Unassigned last.
            .sortedWith(compareBy<SensorRow>({ it.areaName == null }, { it.areaName?.lowercase() }, { it.name.lowercase() }, { it.id }))
        val actions = visible.filter { Placement.actionKind(it.kind) }
            .map { e ->
                val s = states[e.uniqueId]
                ActionRow(
                    e.uniqueId, e.name,
                    areaName = if (e.area == UNASSIGNED_AREA) null else areaNames[e.area] ?: e.area,
                    kind = e.kind,
                    on = (s?.value as? StateValue.OnOff)?.on,
                    available = s?.available ?: true,
                    stale = s?.stale == true,
                    ask = e.ui.securityAsk,
                    switchType = SwitchType.of(e.protocolKind, e.ui),
                )
            }
            .sortedWith(compareBy<ActionRow>({ it.areaName == null }, { it.areaName?.lowercase() }, { it.name.lowercase() }, { it.id }))
        val counts = sensors.filter { it.available && it.active == true }.groupingBy { it.group }.eachCount()
        val summary = SensorGroup.entries.mapNotNull { g -> counts[g]?.let { GroupCount(g, it) } }
        val bySection = sensors.groupBy { SensorSection.of(it.group) }
        val sections = SensorSection.entries.mapNotNull { sec -> bySection[sec]?.let { SectionUi(sec, it) } }
        return SecurityUi(link, panels, summary, sensors, sections, actions, loading = false)
    }

    /** Last-change spec §4.4: the server's time, unless the app saw a later flip itself. */
    private fun since(server: LastChange?, local: Instant?): Since? = when {
        server is LastChange.At -> Since.At(if (local != null && local > server.at) local else server.at)
        local != null -> Since.At(local)
        server is LastChange.OlderThan -> Since.Over(server.window)
        else -> null
    }
}
