package com.gekkotron.oikos.demo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * A fake house published the way zigbee2mqtt and oikos-bridge would: retained HA discovery configs,
 * one JSON state document per device, and a command topic that is echoed back into that document.
 * Thread-safe.
 */
class DemoHouse(private val random: Random = Random(7)) {
    private class Echo(val stateTopic: String, val patch: (String) -> JsonObject?)

    private val configs = mutableListOf<DemoMessage>()
    private val extras = mutableListOf<DemoMessage>()
    private val docs = linkedMapOf<String, JsonObject>()
    private val echoes = mutableMapOf<String, Echo>()

    init {
        areas()
        light("demo_living_lamp", "Floor lamp", "Living room", "demo/living/lamp", on = true, brightness = 180)
        light("demo_kitchen_spots", "Kitchen spots", "Kitchen", "demo/kitchen/spots", on = false, brightness = 255)
        ledStrip("demo_bedroom_strip", "LED strip", "Bedroom", "demo/bedroom/strip")
        switch("demo_coffee", "Coffee machine", "Kitchen", "demo/kitchen/coffee", on = false, available = true)
        switch("demo_garage_plug", "Garage plug", "Garage", "demo/garage/plug", on = false, available = false)
        switch("demo_siren", "Siren", "Hallway", "demo/hallway/siren", on = false, available = true)
        cover("demo_living_shutter", "Shutter", "Living room", "demo/living/shutter")
        cover("demo_living_shutter_side", "Side shutter", "Living room", "demo/living/shutter_side")
        cover("demo_living_door_shutter", "Patio shutter", "Living room", "demo/living/door_shutter", position = false)
        rgbLight("demo_living_ceiling", "Ceiling", "Living room", "demo/living/ceiling")
        plainLight("demo_living_spot", "Spot", "Living room", "demo/living/spot")
        climate("demo_living_ac", "Air conditioner", "Living room", "demo/living/ac")
        sensor("demo_bedroom_temperature", "Bedroom", "Temperature", "Bedroom", "demo/bedroom/climate", "temperature", "temperature", "°C")
        sensor("demo_bedroom_humidity", "Bedroom", "Humidity", "Bedroom", "demo/bedroom/climate", "humidity", "humidity", "%")
        doc("demo/bedroom/climate", obj("temperature" to 19.6, "humidity" to 54))
        sensor("demo_outdoor_temperature", "Outdoor temperature", null, "Outside", OUTDOOR, "temperature", "temperature", "°C")
        doc(OUTDOOR, obj("temperature" to 14.2))
        sensor("demo_solar_power", "Solar production", null, "Garage", ENERGY, "solar", "power", "W")
        sensor("demo_grid_power", "Grid", null, "Garage", ENERGY, "grid", "power", "W")
        sensor("demo_house_power", "House consumption", null, "Garage", ENERGY, "house", "power", "W")
        sensor("demo_water_heater_power", "Water heater", null, "Garage", ENERGY, "water_heater", "power", "W")
        sensor("demo_ac_power", "Air conditioner power", null, "Living room", ENERGY, "ac", "power", "W")
        // Lifetime counters share their power sensor's device so automatic pairing finds them.
        sensor("demo_solar_energy", "Solar production", "Energy", "Garage", ENERGY, "solar_kwh", "energy", "kWh", "total_increasing")
        sensor("demo_grid_energy", "Grid", "Energy", "Garage", ENERGY, "grid_kwh", "energy", "kWh", "total_increasing")
        sensor("demo_house_energy", "House consumption", "Energy", "Garage", ENERGY, "house_kwh", "energy", "kWh", "total_increasing")
        sensor("demo_water_heater_energy", "Water heater", "Energy", "Garage", ENERGY, "water_heater_kwh", "energy", "kWh", "total_increasing")
        sensor("demo_ac_energy", "Air conditioner power", "Energy", "Living room", ENERGY, "ac_kwh", "energy", "kWh", "total_increasing")
        doc(
            ENERGY,
            obj(
                "solar" to 2140, "grid" to -380, "house" to 1760, "water_heater" to 900, "ac" to 350,
                "solar_kwh" to 5821.4, "grid_kwh" to 3290.7, "house_kwh" to 7413.2, "water_heater_kwh" to 1186.9, "ac_kwh" to 642.3,
            ),
        )
        binarySensor("demo_front_door", "Front door", "Hallway", "demo/hallway/door", "door", "contact", onValue = false, initial = true)
        binarySensor("demo_hallway_motion", "Hallway motion", "Hallway", "demo/hallway/motion", "motion", "occupancy", onValue = true, initial = false)
        binarySensor("demo_bedroom_window", "Bedroom window", "Bedroom", "demo/bedroom/window", "window", "contact", onValue = false, initial = false)
        binarySensor("demo_kitchen_smoke", "Kitchen smoke", "Kitchen", "demo/kitchen/smoke", "smoke", "smoke", onValue = true, initial = false)
        binarySensor("demo_garage_leak", "Garage leak", "Garage", "demo/garage/leak", "moisture", "water_leak", onValue = true, initial = false)
        alarm("demo_alarm", "Alarm", "Hallway", ALARM)
        number("demo_water_heater_target", "Water heater target", "Garage", "demo/garage/water_heater")
        vacuum("Living room", "demo/living/vacuum")
        override("demo_outdoor_temperature", """{"cardStyle":"hero_weather"}""")
        override("demo_living_lamp", """{"favorite":true}""")
        override("demo_living_ac", """{"favorite":true}""")
        override("demo_solar_power", """{"energyRole":"solar"}""")
        override("demo_grid_power", """{"energyRole":"grid"}""")
        override("demo_house_power", """{"energyRole":"house"}""")
        override("demo_water_heater_power", """{"energyRole":"consumer","energyIcon":"water_heater"}""")
        override("demo_ac_power", """{"energyRole":"consumer"}""")
        override("demo_siren", """{"securityAction":true}""")
        override("demo_alarm_intrusion", """{"securityAction":true,"securityAsk":false}""")
    }

    /** Configs first, then overrides and areas, then state: the order a real broker replays retained messages. */
    @Synchronized
    fun retainedMessages(): List<DemoMessage> =
        configs + extras + docs.map { (topic, json) -> DemoMessage(topic, json.toString()) }

    /** The state message a real device would publish after receiving [payload] on [topic], or null. */
    @Synchronized
    fun onCommand(topic: String, payload: String): DemoMessage? {
        val echo = echoes[topic] ?: return null
        val patch = echo.patch(payload) ?: return null
        val merged = JsonObject(docs.getValue(echo.stateTopic) + patch)
        docs[echo.stateTopic] = merged
        return DemoMessage(echo.stateTopic, merged.toString())
    }

    /** The house's current state document for [topic], or null when it has none. Delayed echoes publish this, never a snapshot. */
    @Synchronized
    fun current(topic: String): DemoMessage? = docs[topic]?.let { DemoMessage(topic, it.toString()) }

    /** One step of live sensor noise: solar and house drift, grid follows, outdoor temperature wobbles. */
    @Synchronized
    fun tick(): List<DemoMessage> {
        val energy = docs.getValue(ENERGY)
        val solar = (energy.number("solar") + random.nextInt(-150, 151)).coerceIn(0.0, 4200.0)
        val house = (energy.number("house") + random.nextInt(-120, 121)).coerceIn(300.0, 3500.0)
        val heater = (energy.number("water_heater") + random.nextInt(-60, 61)).coerceIn(0.0, 1500.0)
        val ac = (energy.number("ac") + random.nextInt(-40, 41)).coerceIn(0.0, 800.0)
        docs[ENERGY] = obj(
            "solar" to solar.roundToInt(), "grid" to (house - solar).roundToInt(), "house" to house.roundToInt(),
            "water_heater" to heater.roundToInt(), "ac" to ac.roundToInt(),
            "solar_kwh" to counter(energy, "solar_kwh", solar),
            "grid_kwh" to counter(energy, "grid_kwh", (house - solar).coerceAtLeast(0.0)),
            "house_kwh" to counter(energy, "house_kwh", house),
            "water_heater_kwh" to counter(energy, "water_heater_kwh", heater),
            "ac_kwh" to counter(energy, "ac_kwh", ac),
        )
        val outdoor = docs.getValue(OUTDOOR).number("temperature") + random.nextInt(-1, 2) / 10.0
        docs[OUTDOOR] = obj("temperature" to (outdoor * 10).roundToInt() / 10.0)
        val alarm = docs.getValue(ALARM)
        val next = when (alarm.text("state")) {
            "arming" -> alarm.text("target")
            "pending" -> "triggered"
            else -> null
        }
        val alarmMessages = if (next != null) {
            docs[ALARM] = JsonObject(alarm + ("state" to JsonPrimitive(next)))
            listOf(DemoMessage(ALARM, docs.getValue(ALARM).toString()))
        } else {
            emptyList()
        }
        return listOf(DemoMessage(ENERGY, docs.getValue(ENERGY).toString()), DemoMessage(OUTDOOR, docs.getValue(OUTDOOR).toString())) + alarmMessages
    }

    /** A lifetime counter advanced by one tick of [watts], rounded to 0.01 kWh; demo ticks are sped up so it visibly moves. */
    private fun counter(doc: JsonObject, key: String, watts: Double): Double =
        ((doc.number(key) + watts / 1_000 * COUNTER_STEP_HOURS) * 100).roundToInt() / 100.0

    private fun areas() {
        val areas = listOf("Living room", "Kitchen", "Bedroom", "Hallway", "Garage").withIndex().joinToString(",") { (i, name) ->
            """{"id":"${name.lowercase().replace(' ', '_')}","name":"$name","order":${i + 1}}"""
        }
        extras += DemoMessage("oikos/ui/_areas", """{"areas":[$areas]}""")
    }

    private fun override(id: String, json: String) {
        extras += DemoMessage("oikos/ui/$id", json)
    }

    private fun config(component: String, id: String, device: String, name: String?, area: String, body: JsonObjectBuilder.() -> Unit) {
        val json = buildJsonObject {
            put("unique_id", id)
            if (name == null) put("name", JsonPrimitive(null as String?)) else put("name", name)
            put("device", buildJsonObject {
                putJsonArray("identifiers") { add("demo_" + device.lowercase().replace(' ', '_')) }
                put("name", device)
                put("manufacturer", "Oikos demo")
                put("suggested_area", area)
            })
            body()
        }
        configs += DemoMessage("homeassistant/$component/oikos_demo/$id/config", json.toString())
    }

    private fun doc(topic: String, json: JsonObject) {
        docs[topic] = json
    }

    private fun echo(commandTopic: String, stateTopic: String, patch: (String) -> JsonObject?) {
        echoes[commandTopic] = Echo(stateTopic, patch)
    }

    private fun light(id: String, device: String, area: String, topic: String, on: Boolean, brightness: Int) {
        config("light", id, device, null, area) {
            put("schema", "json")
            put("brightness", true)
            put("brightness_scale", 255)
            put("state_topic", topic)
            put("command_topic", "$topic/set")
        }
        doc(topic, obj("state" to onOff(on), "brightness" to brightness))
        echo("$topic/set", topic) { p -> runCatching { Json.parseToJsonElement(p).jsonObject }.getOrNull() }
    }

    /** A colour bulb: JSON light with brightness and xy colour, off at start. */
    private fun rgbLight(id: String, device: String, area: String, topic: String) {
        config("light", id, device, null, area) {
            put("schema", "json")
            put("brightness", true)
            put("brightness_scale", 255)
            putJsonArray("supported_color_modes") { add("xy") }
            put("state_topic", topic)
            put("command_topic", "$topic/set")
        }
        doc(
            topic,
            buildJsonObject {
                put("state", "OFF")
                put("brightness", 200)
                put("color", buildJsonObject { put("x", 0.45); put("y", 0.41) })
            },
        )
        echo("$topic/set", topic) { p -> runCatching { Json.parseToJsonElement(p).jsonObject }.getOrNull() }
    }

    /** An on/off-only light. */
    private fun plainLight(id: String, device: String, area: String, topic: String) {
        config("light", id, device, null, area) {
            put("schema", "json")
            put("state_topic", topic)
            put("command_topic", "$topic/set")
        }
        doc(topic, obj("state" to "OFF"))
        echo("$topic/set", topic) { p -> runCatching { Json.parseToJsonElement(p).jsonObject }.getOrNull() }
    }

    /** An addressable strip: a JSON light with effects and xy colour, plus speed and intensity numbers on the same device. */
    private fun ledStrip(id: String, device: String, area: String, topic: String) {
        val effects = listOf("solid", "breathe", "rainbow", "candle")
        config("light", id, device, null, area) {
            put("schema", "json")
            put("brightness", true)
            put("brightness_scale", 255)
            putJsonArray("supported_color_modes") { add("xy") }
            put("effect", true)
            putJsonArray("effect_list") { effects.forEach { add(it) } }
            put("state_topic", topic)
            put("command_topic", "$topic/set")
        }
        for ((field, label) in listOf("effect_speed" to "Effect speed", "effect_intensity" to "Effect intensity")) {
            config("number", "${id}_$field", device, label, area) {
                put("state_topic", topic)
                put("value_template", "{{ value_json.$field }}")
                put("command_topic", "$topic/set/$field")
                put("min", 0)
                put("max", 255)
                put("step", 1)
            }
            echo("$topic/set/$field", topic) { p -> p.toDoubleOrNull()?.let { obj(field to it.roundToInt().coerceIn(0, 255)) } }
        }
        doc(
            topic,
            buildJsonObject {
                put("state", "ON")
                put("brightness", 160)
                put("effect", "rainbow")
                put("color", buildJsonObject { put("x", 0.196); put("y", 0.203) })
                put("effect_speed", 128)
                put("effect_intensity", 128)
            },
        )
        echo("$topic/set", topic) { p -> runCatching { Json.parseToJsonElement(p).jsonObject }.getOrNull() }
    }

    private fun switch(id: String, device: String, area: String, topic: String, on: Boolean, available: Boolean) {
        config("switch", id, device, null, area) {
            put("state_topic", topic)
            put("value_template", "{{ value_json.state }}")
            put("command_topic", "$topic/set")
            if (!available) put("availability_topic", "$topic/availability")
        }
        doc(topic, obj("state" to onOff(on)))
        if (!available) extras += DemoMessage("$topic/availability", "offline")
        echo("$topic/set", topic) { p -> if (p == "ON" || p == "OFF") obj("state" to p) else null }
    }

    /** [position] false: a shutter that only reports open or closed (room groups demo, spec §9). */
    private fun cover(id: String, device: String, area: String, topic: String, position: Boolean = true) {
        config("cover", id, device, null, area) {
            put("device_class", "shutter")
            put("state_topic", topic)
            put("value_template", "{{ value_json.state }}")
            if (position) {
                put("position_topic", topic)
                put("position_template", "{{ value_json.position }}")
                put("set_position_topic", "$topic/position/set")
            }
            put("command_topic", "$topic/set")
        }
        doc(topic, if (position) obj("state" to "open", "position" to 60) else obj("state" to "closed"))
        echo("$topic/set", topic) { p ->
            when (p) {
                "OPEN" -> if (position) obj("state" to "open", "position" to 100) else obj("state" to "open")
                "CLOSE" -> if (position) obj("state" to "closed", "position" to 0) else obj("state" to "closed")
                "STOP" -> obj("state" to "stopped")
                else -> null
            }
        }
        if (position) {
            echo("$topic/position/set", topic) { p ->
                p.toIntOrNull()?.coerceIn(0, 100)?.let { obj("state" to if (it == 0) "closed" else "open", "position" to it) }
            }
        }
    }

    private fun climate(id: String, device: String, area: String, topic: String) {
        val modes = listOf("off", "cool", "heat", "auto")
        val fanModes = listOf("auto", "silence", "1", "2", "3", "4", "5")
        config("climate", id, device, null, area) {
            putJsonArray("modes") { modes.forEach { add(it) } }
            put("min_temp", 16)
            put("max_temp", 30)
            put("temp_step", 0.5)
            put("current_temperature_topic", topic)
            put("current_temperature_template", "{{ value_json.current }}")
            put("temperature_state_topic", topic)
            put("temperature_state_template", "{{ value_json.target }}")
            put("mode_state_topic", topic)
            put("mode_state_template", "{{ value_json.mode }}")
            put("temperature_command_topic", "$topic/temperature/set")
            put("mode_command_topic", "$topic/mode/set")
            putJsonArray("fan_modes") { fanModes.forEach { add(it) } }
            put("fan_mode_state_topic", topic)
            put("fan_mode_state_template", "{{ value_json.fan_mode }}")
            put("fan_mode_command_topic", "$topic/fan_mode/set")
        }
        doc(topic, obj("current" to 22.4, "target" to 21.0, "mode" to "heat", "fan_mode" to "auto"))
        echo("$topic/temperature/set", topic) { p -> p.toDoubleOrNull()?.let { obj("target" to it) } }
        echo("$topic/mode/set", topic) { p -> if (p in modes) obj("mode" to p) else null }
        echo("$topic/fan_mode/set", topic) { p -> if (p in fanModes) obj("fan_mode" to p) else null }
    }

    private fun sensor(
        id: String, device: String, name: String?, area: String, topic: String,
        field: String, deviceClass: String, unit: String, stateClass: String = "measurement",
    ) = config("sensor", id, device, name, area) {
        put("state_topic", topic)
        put("value_template", "{{ value_json.$field }}")
        put("device_class", deviceClass)
        put("unit_of_measurement", unit)
        put("state_class", stateClass)
    }

    /** zigbee2mqtt style: JSON booleans for payload_on/off (a door contact is `true` when closed). */
    private fun binarySensor(
        id: String, device: String, area: String, topic: String,
        deviceClass: String, field: String, onValue: Boolean, initial: Boolean,
    ) {
        config("binary_sensor", id, device, null, area) {
            put("state_topic", topic)
            put("value_template", "{{ value_json.$field }}")
            put("device_class", deviceClass)
            put("payload_on", onValue)
            put("payload_off", !onValue)
        }
        doc(topic, obj(field to initial))
    }

    /** A remote-code panel like the spec's bridge (security spec Appendix A); delays advance on [tick]. */
    private fun alarm(id: String, device: String, area: String, topic: String) {
        config("alarm_control_panel", id, device, null, area) {
            put("state_topic", topic)
            put("value_template", "{{ value_json.state }}")
            put("command_topic", "$topic/set")
            put("code", "REMOTE_CODE")
            put("code_arm_required", false)
            put("code_disarm_required", true)
            put("command_template", """{"action":"{{ action }}","code":"{{ code }}"}""")
            putJsonArray("supported_features") { listOf("arm_home", "arm_away", "arm_night").forEach { add(it) } }
        }
        doc(topic, obj("state" to "disarmed"))
        echo("$topic/set", topic) { p -> alarmCommand(topic, p) }
        config("button", "demo_alarm_intrusion", device, "Simulate intrusion", area) { put("command_topic", "$topic/intrude") }
        echo("$topic/intrude", topic) { if (docs.getValue(topic).text("state").startsWith("armed_")) obj("state" to "pending") else null }
    }

    /** Arming goes through `arming`; disarming needs [DEMO_CODE], and a wrong code republishes the current state. */
    private fun alarmCommand(topic: String, payload: String): JsonObject? {
        val command = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val action = command.text("action")
        return when {
            action == "DISARM" -> if (command.text("code") == DEMO_CODE) obj("state" to "disarmed") else obj("state" to docs.getValue(topic).text("state"))
            action in ARMED -> obj("state" to "arming", "target" to ARMED.getValue(action))
            else -> null
        }
    }

    private fun number(id: String, device: String, area: String, topic: String) {
        config("number", id, device, null, area) {
            put("state_topic", topic)
            put("value_template", "{{ value_json.target }}")
            put("command_topic", "$topic/target/set")
            put("min", 40)
            put("max", 65)
            put("step", 1)
            put("unit_of_measurement", "°C")
        }
        doc(topic, obj("target" to 55))
        echo("$topic/target/set", topic) { p -> p.toDoubleOrNull()?.let { obj("target" to it) } }
    }

    private fun vacuum(area: String, topic: String) {
        val options = listOf("quiet", "balanced", "turbo")
        config("select", "demo_vacuum_mode", "Vacuum", "Power", area) {
            put("state_topic", topic)
            put("value_template", "{{ value_json.mode }}")
            put("command_topic", "$topic/mode/set")
            putJsonArray("options") { options.forEach { add(it) } }
        }
        config("button", "demo_vacuum_start", "Vacuum", "Start", area) {
            put("command_topic", "$topic/start")
        }
        doc(topic, obj("mode" to "balanced"))
        echo("$topic/mode/set", topic) { p -> if (p in options) obj("mode" to p) else null }
    }

    private companion object {
        const val ENERGY = "demo/energy"
        const val COUNTER_STEP_HOURS = 0.05
        const val OUTDOOR = "demo/outdoor"
        const val ALARM = "demo/alarm"
        const val DEMO_CODE = "1234"
        val ARMED = mapOf("ARM_HOME" to "armed_home", "ARM_AWAY" to "armed_away", "ARM_NIGHT" to "armed_night")

        fun onOff(on: Boolean) = if (on) "ON" else "OFF"

        fun obj(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
            for ((k, v) in pairs) when (v) {
                is String -> put(k, v)
                is Boolean -> put(k, v)
                is Number -> put(k, v)
                else -> error("unsupported demo value $v")
            }
        }

        fun JsonObject.number(key: String): Double = (get(key) as JsonPrimitive).doubleOrNull ?: 0.0
        fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
}
