package com.gekkotron.oikos.feature.widget

import com.gekkotron.oikos.feature.widget.model.TileIcon
import com.gekkotron.oikos.feature.widget.model.TileSnapshot
import com.gekkotron.oikos.feature.widget.model.TileStatus
import com.gekkotron.oikos.feature.widget.model.TileValue
import com.gekkotron.oikos.history.HistoryRefs
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import kotlin.math.roundToInt

/** Entity + state → what the widget stores (widgets spec §3.2). Pure. */
object TileMapper {
    /**
     * [previous] carries memory across refreshes (last climate mode, last live time). [complete] = discovery settled, so a
     * missing entity really is gone. Returns null for an id never seen at all.
     */
    fun map(entity: Entity?, state: EntityState?, previous: TileSnapshot?, historyEnabled: Boolean, complete: Boolean): TileSnapshot? {
        if (entity == null) {
            return when {
                previous == null -> null
                complete -> previous.copy(status = TileStatus.REMOVED, pendingSince = null)
                else -> previous
            }
        }
        val liveAt = state?.lastUpdate?.takeIf { !state.stale }
        val caps = entity.capabilities
        // Before discovery settles the config may come from the cache, where the alarm code is redacted (so "no code"):
        // never drop a code requirement seen earlier until the live config has replayed.
        val keep = if (complete) null else previous
        val codeArm = caps.codeArmRequired || keep?.codeArmRequired == true
        val codeDisarm = caps.codeDisarmRequired || keep?.codeDisarmRequired == true
        val codeFormat = if (!caps.codeArmRequired && !caps.codeDisarmRequired && keep != null && (codeArm || codeDisarm)) keep.codeFormat else caps.codeFormat
        val climateMode = (state?.value as? StateValue.Climate)?.mode
        return TileSnapshot(
            uniqueId = entity.uniqueId,
            // The source has already folded the override name into Entity.name.
            name = entity.name,
            kind = entity.kind,
            icon = iconOf(entity),
            value = state?.value?.let { valueOf(entity, it) } ?: TileValue.None,
            available = state?.available ?: true,
            alarmModes = entity.capabilities.alarmModes,
            codeArmRequired = codeArm,
            codeDisarmRequired = codeDisarm,
            codeFormat = codeFormat,
            hvacModes = entity.capabilities.hvacModes,
            lastOnMode = climateMode?.takeIf { it != "off" } ?: previous?.lastOnMode,
            historyAvailable = historyEnabled && HistoryRefs.of(entity) != null,
            status = TileStatus.OK,
            pendingSince = null,
            updatedAt = liveAt?.toEpochMilli() ?: previous?.updatedAt,
        )
    }

    private fun valueOf(entity: Entity, value: StateValue): TileValue = when (value) {
        StateValue.Unknown, StateValue.Unsupported -> TileValue.None
        is StateValue.OnOff -> if (entity.kind == Kind.BINARY_SENSOR) TileValue.Contact(entity.deviceClass, value.on) else TileValue.OnOff(value.on)
        is StateValue.Light -> TileValue.Light(value.on, value.brightness?.let { (it * 100 / 255.0).roundToInt() })
        is StateValue.Numeric -> TileValue.Measure(value.value, entity.unit)
        is StateValue.Text -> TileValue.Text(value.value)
        is StateValue.Cover -> TileValue.Cover(value.state, value.position)
        is StateValue.Climate -> TileValue.Climate(value.mode, value.current, value.target)
        is StateValue.Alarm -> TileValue.Alarm(value.state)
        is StateValue.Vacuum -> value.state?.let { TileValue.Text(it) } ?: TileValue.None
    }

    private fun iconOf(entity: Entity): TileIcon = when (entity.kind) {
        Kind.LIGHT -> TileIcon.LIGHT
        Kind.SWITCH -> TileIcon.SWITCH
        Kind.COVER -> TileIcon.COVER
        Kind.CLIMATE -> TileIcon.CLIMATE
        Kind.ALARM_PANEL -> TileIcon.ALARM
        Kind.BUTTON -> TileIcon.BUTTON
        Kind.SENSOR -> if (entity.deviceClass == "temperature") TileIcon.TEMPERATURE else TileIcon.SENSOR
        Kind.BINARY_SENSOR, Kind.NUMBER, Kind.SELECT, Kind.VACUUM -> TileIcon.SENSOR
    }
}
