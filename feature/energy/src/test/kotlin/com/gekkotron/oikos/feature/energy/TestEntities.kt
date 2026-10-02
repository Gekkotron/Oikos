package com.gekkotron.oikos.feature.energy

import com.gekkotron.oikos.model.Bindings
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.model.UiOverride

object NoBindings : Bindings

fun energyEntity(
    id: String,
    role: EnergyRole?,
    unit: String? = "W",
    name: String = id,
    invert: Boolean = false,
    order: Int? = null,
    icon: String? = null,
) = Entity(
    id, null, name, Kind.SENSOR, "power", unit, null, Capabilities(), NoBindings, UNASSIGNED_AREA,
    UiOverride(energyRole = role, energyInvert = invert, order = order, energyIcon = icon),
)

fun numeric(id: String, value: Double, stale: Boolean = false, available: Boolean = true) =
    EntityState(id, StateValue.Numeric(value), available = available, stale = stale)

fun unknown(id: String) = EntityState(id, StateValue.Unknown)
