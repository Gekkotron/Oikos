package com.gekkotron.oikos.feature.security.presentation

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.SensorGroup
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.transport.LinkState

/** Entities → the Security tab (security spec §4.3). Pure. */
object SecurityUiBuilder {
    fun build(entities: Map<String, Entity>, states: Map<String, EntityState>, areas: List<Area>, link: LinkState): SecurityUi {
        val areaNames = areas.associate { it.id to it.name }
        val visible = entities.values.filter { !it.ui.hidden && !it.ui.securityHidden && it.partOf == null }
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
                )
            }
            .sortedWith(compareBy<SensorRow>({ rank(it) }, { it.areaName == null }, { it.areaName?.lowercase() }, { it.name.lowercase() }, { it.id }))
        val counts = sensors.filter { it.available && it.active == true }.groupingBy { it.group }.eachCount()
        val summary = SensorGroup.entries.mapNotNull { g -> counts[g]?.let { GroupCount(g, it) } }
        val bySection = sensors.groupBy { SensorSection.of(it.group) }
        val sections = SensorSection.entries.mapNotNull { sec -> bySection[sec]?.let { SectionUi(sec, it) } }
        return SecurityUi(link, panels, summary, sensors, sections, loading = false)
    }

    /** 0 active danger, 1 active intrusion, 2 inactive or unknown, 3 unavailable. */
    private fun rank(row: SensorRow): Int = when {
        !row.available -> 3
        row.active == true && row.group.danger -> 0
        row.active == true -> 1
        else -> 2
    }
}
