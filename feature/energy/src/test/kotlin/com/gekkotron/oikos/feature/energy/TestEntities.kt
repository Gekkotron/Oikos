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
import com.gekkotron.oikos.state.mqtt.MqttBindings
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

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

/** Bound like a Z2M sensor on `zigbee2mqtt/<id>`, reading [key] from its payload, so history can find it. */
fun Entity.recorded(key: String) = copy(
    bindings = MqttBindings(
        "sensor",
        buildJsonObject {
            put("state_topic", JsonPrimitive("zigbee2mqtt/$uniqueId"))
            put("value_template", JsonPrimitive("{{ value_json.$key }}"))
        },
    ),
)
