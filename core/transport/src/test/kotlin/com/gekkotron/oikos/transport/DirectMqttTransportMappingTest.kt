package com.gekkotron.oikos.transport

import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Focused, broker-free coverage of [toMqttMessage]: does it forward the RETAIN bit (and topic/payload)
 * from a real HiveMQ [Mqtt3Publish] into [MqttMessage]? This is the behaviour the embedded-Moquette
 * integration test can no longer verify (see DirectMqttTransportTest's `receives retained messages on
 * subscribe` comment and task-2-report.md): Moquette 0.17 cannot redeliver a retained message with the
 * wire bit correctly set at any subscribable QoS, so that test only proves delivery, not the flag.
 */
class DirectMqttTransportMappingTest {
    @Test
    fun `a retained publish maps to a retained message`() {
        val publish: Mqtt3Publish = Mqtt3Publish.builder()
            .topic("homeassistant/light/a/config")
            .payload("{}".encodeToByteArray())
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(true)
            .build()

        val message = publish.toMqttMessage()

        assertEquals("homeassistant/light/a/config", message.topic)
        assertEquals("{}", message.payloadString())
        assertTrue(message.retained)
    }

    @Test
    fun `a non-retained publish maps to a non-retained message`() {
        val publish: Mqtt3Publish = Mqtt3Publish.builder()
            .topic("cmd/lamp")
            .payload("ON".encodeToByteArray())
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(false)
            .build()

        val message = publish.toMqttMessage()

        assertEquals("cmd/lamp", message.topic)
        assertEquals("ON", message.payloadString())
        assertFalse(message.retained)
    }
}
