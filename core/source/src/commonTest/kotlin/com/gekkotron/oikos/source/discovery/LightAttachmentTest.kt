package com.gekkotron.oikos.source.discovery

import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.state.mqtt.MqttBindings
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class LightAttachmentTest {
    private fun entity(id: String, kind: Kind, deviceId: String?) = Entity(
        uniqueId = id,
        deviceId = deviceId,
        name = id,
        kind = kind,
        deviceClass = null,
        unit = null,
        icon = null,
        capabilities = Capabilities(),
        bindings = MqttBindings(kind.name.lowercase(), buildJsonObject { }),
    )

    @Test
    fun `numbers and selects fold into the device's single light`() {
        val all = listOf(
            entity("speed", Kind.NUMBER, "strip"),
            entity("leds", Kind.LIGHT, "strip"),
            entity("mode", Kind.SELECT, "strip"),
        )
        assertEquals(mapOf("speed" to "leds", "mode" to "leds"), LightAttachment.compute(all))
    }

    @Test
    fun `nothing folds when the device has two lights`() {
        val all = listOf(entity("l1", Kind.LIGHT, "dual"), entity("l2", Kind.LIGHT, "dual"), entity("speed", Kind.NUMBER, "dual"))
        assertEquals(emptyMap(), LightAttachment.compute(all))
    }

    @Test
    fun `only numbers and selects fold, and only on the same device`() {
        val all = listOf(
            entity("leds", Kind.LIGHT, "strip"),
            entity("occupancy", Kind.BINARY_SENSOR, "strip"),
            entity("temp", Kind.SENSOR, "strip"),
            entity("plug", Kind.SWITCH, "strip"),
            entity("target", Kind.NUMBER, "heater"),
            entity("orphan", Kind.NUMBER, null),
        )
        assertEquals(emptyMap(), LightAttachment.compute(all))
    }
}
