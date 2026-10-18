package com.gekkotron.oikos.state

import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.state.mqtt.MqttBindings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

fun bindings(component: String, json: String) = MqttBindings(component, Json.parseToJsonElement(json).jsonObject)

fun testEntity(id: String, kind: Kind, component: String, json: String) = Entity(
    uniqueId = id, deviceId = null, name = id, kind = kind, deviceClass = null, unit = null, icon = null,
    capabilities = Capabilities(), bindings = bindings(component, json),
)
