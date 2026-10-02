package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Hs
import com.gekkotron.oikos.model.Rgb
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.ColorMath
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

    private val RGB_MODES = setOf("rgb", "rgbw", "rgbww")

    /** `{x,y}`, then `{h|hue, s|saturation}` (s in 0..100), then `{r,g,b}`; null when none is complete. */
    private fun colorOf(o: JsonObject): Hs? {
        fun num(vararg keys: String) = keys.firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.doubleOrNull }
        val x = num("x")
        val y = num("y")
        if (x != null && y != null) return ColorMath.xyToHs(ColorMath.Xy(x, y))
        val h = num("h", "hue")
        val s = num("s", "saturation")
        if (h != null && s != null) return Hs(((h % 360) + 360) % 360, (s / 100).coerceIn(0.0, 1.0))
        val r = num("r")
        val g = num("g")
        val bl = num("b")
        if (r != null && g != null && bl != null) {
            return ColorMath.rgbToHs(Rgb(r.roundToInt().coerceIn(0, 255), g.roundToInt().coerceIn(0, 255), bl.roundToInt().coerceIn(0, 255)))
        }
        return null
    }

    /** The colour in the light's own mode (spec §3.3), or null when it has no colour mode. */
    private fun colorJson(b: MqttBindings, hs: Hs): JsonObject? {
        val modes = b.strList("supported_color_modes").orEmpty()
        return when {
            "xy" in modes -> ColorMath.hsToXy(hs).let { buildJsonObject { put("x", it.x); put("y", it.y) } }
            "hs" in modes -> buildJsonObject { put("h", round1(hs.hue)); put("s", round1(hs.saturation * 100)) }
            modes.any { it in RGB_MODES } -> ColorMath.hsToRgb(hs).let { c -> buildJsonObject { put("r", c.r); put("g", c.g); put("b", c.b) } }
            else -> null
        }
    }

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0

    private fun StateValue.asLight(): StateValue.Light = this as? StateValue.Light ?: StateValue.Light(on = isOn())

    override fun stateTopics(b: MqttBindings): Set<String> =
        if (isJson(b)) setOfNotNull(b.str("state_topic"))
        else setOfNotNull(b.str("state_topic"), b.str("brightness_state_topic"), b.str("effect_state_topic"), b.str("rgb_state_topic"))

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
        val effect = (obj["effect"] as? JsonPrimitive)?.contentOrNull
        val color = (obj["color"] as? JsonObject)?.let(::colorOf)
        if (stateWord == null && rawBrightness == null && effect == null && color == null) return Decoded.Ignored
        val on = when (stateWord) {
            "ON" -> true
            "OFF" -> false
            else -> prev.on
        }
        val brightness = rawBrightness?.let { toByte(it, scale(b)) } ?: prev.brightness
        return Decoded.State(StateValue.Light(on, brightness, effect ?: prev.effect, color ?: prev.color))
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
        if (topic == b.str("effect_state_topic")) {
            parts.part(renderIncoming(b, "effect_value_template", payload)) { v ->
                val effect = v.asPayloadString()?.takeIf { it.isNotEmpty() } ?: return@part false
                light = light.copy(effect = effect)
                true
            }
        }
        if (topic == b.str("rgb_state_topic")) {
            parts.part(renderIncoming(b, "rgb_value_template", payload)) { v ->
                val channels = v.asPayloadString()?.split(',')?.map { it.trim().toIntOrNull() }
                if (channels == null || channels.size != 3 || channels.any { it == null || it !in 0..255 }) return@part false
                light = light.copy(color = ColorMath.rgbToHs(Rgb(channels[0]!!, channels[1]!!, channels[2]!!)))
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
                turnOn?.effect?.let { put("effect", it) }
                turnOn?.color?.let { hs -> colorJson(b, hs)?.let { put("color", it) } }
            }
            return Encoded.Publish(listOf(outgoing(b, topic, body.toString())))
        }
        val messages = mutableListOf<Outgoing>()
        val brightness = turnOn?.brightness
        val brightnessTopic = b.str("brightness_command_topic")
        val brightnessOnly = brightness != null && brightnessTopic != null && b.str("on_command_type") == "brightness"
        if (!brightnessOnly) messages += outgoing(b, topic, if (on) b.str("payload_on", "ON") else b.str("payload_off", "OFF"))
        if (brightness != null && brightnessTopic != null) messages += outgoing(b, brightnessTopic, fromByte(brightness, scale(b)).toString())
        turnOn?.effect?.let { effect ->
            (publish(b, "effect_command_topic", "effect_command_template", effect) as? Encoded.Publish)?.let { messages += it.messages }
        }
        turnOn?.color?.let { hs ->
            val c = ColorMath.hsToRgb(hs)
            b.str("rgb_command_topic")?.let { messages += outgoing(b, it, "${c.r},${c.g},${c.b}") }
        }
        return Encoded.Publish(messages)
    }

    override fun predict(command: Command, previous: StateValue): StateValue? {
        val on = targetOn(command, previous) ?: return null
        val prev = previous.asLight()
        val turnOn = command as? Command.TurnOn
        return StateValue.Light(on, turnOn?.brightness ?: prev.brightness, turnOn?.effect ?: prev.effect, turnOn?.color ?: prev.color)
    }
}
