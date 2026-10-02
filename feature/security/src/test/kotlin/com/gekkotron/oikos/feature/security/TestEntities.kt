package com.gekkotron.oikos.feature.security

import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Bindings
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.model.UiOverride

object NoBindings : Bindings

fun sensor(id: String, deviceClass: String?, name: String = id, area: String = UNASSIGNED_AREA, ui: UiOverride = UiOverride(), partOf: String? = null) =
    Entity(id, null, name, Kind.BINARY_SENSOR, deviceClass, null, null, Capabilities(), NoBindings, area, ui, partOf)

fun panel(id: String = "alarm", name: String = "Alarm", order: Int? = null, ui: UiOverride = UiOverride(order = order)) = Entity(
    id, null, name, Kind.ALARM_PANEL, null, null, null,
    Capabilities(alarmModes = setOf(AlarmMode.HOME, AlarmMode.AWAY), codeDisarmRequired = true), NoBindings, UNASSIGNED_AREA, ui,
)

fun on(id: String, on: Boolean, available: Boolean = true) = EntityState(id, StateValue.OnOff(on), available = available)

fun alarm(id: String, state: AlarmState, stale: Boolean = false) = EntityState(id, StateValue.Alarm(state), stale = stale)
