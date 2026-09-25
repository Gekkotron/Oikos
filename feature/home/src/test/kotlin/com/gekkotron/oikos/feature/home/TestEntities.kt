package com.gekkotron.oikos.feature.home

import com.gekkotron.oikos.model.Bindings
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.model.UiOverride

object NoBindings : Bindings

fun testEntity(
    id: String,
    kind: Kind,
    name: String = id,
    area: String = UNASSIGNED_AREA,
    deviceClass: String? = null,
    unit: String? = null,
    ui: UiOverride = UiOverride(),
    capabilities: Capabilities = Capabilities(),
    deviceId: String? = null,
) = Entity(id, deviceId, name, kind, deviceClass, unit, null, capabilities, NoBindings, area, ui)
