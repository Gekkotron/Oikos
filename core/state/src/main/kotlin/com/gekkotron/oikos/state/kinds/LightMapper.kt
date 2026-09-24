package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.state.template.asDouble
import com.gekkotron.oikos.state.template.asPayloadString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

object LightMapper : KindMapper {
    private fun isJson(b: MqttBindings) = b.str("schema") == "json"

    private fun scale(b: MqttBindings): Double = b.double("brightness_scale")?.takeIf { it > 0 } ?: 255.0

    private fun toByte(raw: Double, scale: Double): Int = (raw / scale * 255).roundToInt().coerceIn(0, 255)

    private fun fromByte(value: Int, scale: Double): Int = (value / 255.0 * scale).roundToInt()

    private fun StateValue.asLight(): StateValue.Light = this as? StateValue.Light ?: StateValue.Light(on = isOn())

    override fun stateTopics(b: MqttBindings): Set<String> =
        if (isJson(b)) setOfNotNull(b.str("state_topic"))
        else setOfNotNull(b.str("state_topic"), b.str("brightness_state_topic"))

    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded =
        if (isJson(b)) decodeJson(b, payload, previous) else decodeDefault(b, topic, payload, previous)

    private fun decodeJson(b: MqttBindings, payload: String, previous: StateValue): Decoded {
        val obj = try {
            Json.parseToJsonElement(payload) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return Decoded.Error("JSON light payload is not an object")
        val prev = previous.asLight()
        val stateWord = (obj["state"] as? JsonPrimitive)?.contentOrNull
        val rawBrightness = (obj["brightness"] as? JsonPrimitive)?.doubleOrNull
        if (stateWord == null && rawBrightness == null) return Decoded.Ignored
        val on = when (stateWord) {
            "ON" -> true
            "OFF" -> false
            else -> prev.on
        }
        val brightness = rawBrightness?.let { toByte(it, scale(b)) } ?: prev.brightness
        return Decoded.State(StateValue.Light(on, brightness))
    }

    private fun decodeDefault(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded {
        var light = previous.asLight()
        val parts = PartialDecode()
        if (topic == b.str("state_topic")) {
            parts.part(renderIncoming(b, "state_value_template", payload)) { v ->
                when (v.asPayloadString()) {
                    b.str("payload_on", "ON") -> { light = light.copy(on = true); true }
                    b.str("payload_off", "OFF") -> { light = light.copy(on = false); true }
                    else -> false
                }
            }
        }
        if (topic == b.str("brightness_state_topic")) {
            parts.part(renderIncoming(b, "brightness_value_template", payload)) { v ->
                val raw = v.asDouble() ?: return@part false
                light = light.copy(brightness = toByte(raw, scale(b)))
                true
            }
        }
        return parts.result(light)
    }

    override fun encode(b: MqttBindings, command: Command, previous: StateValue): Encoded {
        val on = targetOn(command, previous) ?: return Encoded.NotSupported("light cannot $command")
        val topic = b.str("command_topic") ?: return Encoded.NotSupported("no command_topic configured")
        val turnOn = (command as? Command.TurnOn)?.takeIf { on }
        if (isJson(b)) {
            val body = buildJsonObject {
                put("state", if (on) "ON" else "OFF")
                turnOn?.brightness?.let { put("brightness", fromByte(it, scale(b))) }
                turnOn?.color?.let { c ->
                    put("color", buildJsonObject { put("r", c.r); put("g", c.g); put("b", c.b) })
                }
            }
            return Encoded.Publish(listOf(outgoing(b, topic, body.toString())))
        }
        val messages = mutableListOf<Outgoing>()
        val brightness = turnOn?.brightness
        val brightnessTopic = b.str("brightness_command_topic")
        val brightnessOnly = brightness != null && brightnessTopic != null && b.str("on_command_type") == "brightness"
        if (!brightnessOnly) messages += outgoing(b, topic, if (on) b.str("payload_on", "ON") else b.str("payload_off", "OFF"))
        if (brightness != null && brightnessTopic != null) messages += outgoing(b, brightnessTopic, fromByte(brightness, scale(b)).toString())
        turnOn?.color?.let { c -> b.str("rgb_command_topic")?.let { messages += outgoing(b, it, "${c.r},${c.g},${c.b}") } }
        return Encoded.Publish(messages)
    }

    override fun predict(command: Command, previous: StateValue): StateValue? {
        val on = targetOn(command, previous) ?: return null
        val prev = previous.asLight()
        return StateValue.Light(on, (command as? Command.TurnOn)?.brightness ?: prev.brightness)
    }
}
