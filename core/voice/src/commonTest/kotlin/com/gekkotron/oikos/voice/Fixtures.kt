package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Bindings
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.UiOverride

private object NoBindings : Bindings

fun entity(
    id: String,
    name: String,
    kind: Kind,
    area: String = "unassigned",
    deviceClass: String? = null,
    brightness: Boolean = false,
    position: Boolean = false,
    hidden: Boolean = false,
    partOf: String? = null,
    override: UiOverride = UiOverride(),
    unit: String? = null,
): Entity = Entity(
    uniqueId = id,
    deviceId = null,
    name = name,
    kind = kind,
    deviceClass = deviceClass,
    unit = unit,
    icon = null,
    capabilities = Capabilities(brightness = brightness, position = position),
    bindings = NoBindings,
    area = area,
    ui = override.copy(hidden = hidden),
    partOf = partOf,
)

fun area(id: String, name: String): Area = Area(id = id, name = name)
