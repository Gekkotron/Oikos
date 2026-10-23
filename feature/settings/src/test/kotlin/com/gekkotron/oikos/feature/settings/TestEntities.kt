package com.gekkotron.oikos.feature.settings

import com.gekkotron.oikos.model.Bindings
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.UNASSIGNED_AREA

object NoBindings : Bindings

fun testEntity(id: String, kind: Kind, area: String = UNASSIGNED_AREA): Entity =
    Entity(id, null, id, kind, null, null, null, Capabilities(), NoBindings, area)
