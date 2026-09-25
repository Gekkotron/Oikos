package com.gekkotron.oikos.source.discovery

import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Device
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.state.kinds.AlarmPanelMapper
import com.gekkotron.oikos.state.kinds.ClimateMapper
import com.gekkotron.oikos.state.kinds.NumberMapper
import com.gekkotron.oikos.state.mqtt.MqttBindings
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class DiscoveredEntity(val entity: Entity, val device: Device?)

sealed interface DiscoveryResult {
    data object NotDiscovery : DiscoveryResult
    data object Removed : DiscoveryResult
    data class Skipped(val reason: String) : DiscoveryResult
    data class Entities(val items: List<DiscoveredEntity>, val skipped: List<String> = emptyList()) : DiscoveryResult
}

object DiscoveryParser {
    private val KINDS = mapOf(
        "light" to Kind.LIGHT,
        "switch" to Kind.SWITCH,
        "cover" to Kind.COVER,
        "climate" to Kind.CLIMATE,
        "sensor" to Kind.SENSOR,
        "binary_sensor" to Kind.BINARY_SENSOR,
        "alarm_control_panel" to Kind.ALARM_PANEL,
        "number" to Kind.NUMBER,
        "select" to Kind.SELECT,
        "button" to Kind.BUTTON,
    )
    private val REQUIRES_COMMAND = setOf(Kind.SWITCH, Kind.LIGHT, Kind.NUMBER, Kind.SELECT, Kind.BUTTON, Kind.ALARM_PANEL)
    private val REQUIRES_STATE = setOf(Kind.SENSOR, Kind.BINARY_SENSOR)
    private val LIGHT_SCHEMAS = setOf("default", "basic", "json")

    private sealed interface Built {
        data class Ok(val item: DiscoveredEntity) : Built
        data class Bad(val reason: String) : Built
    }

    fun parse(prefix: String, topic: String, payload: String): DiscoveryResult {
        if (!topic.startsWith("$prefix/")) return DiscoveryResult.NotDiscovery
        val parts = topic.removePrefix("$prefix/").split('/')
        if (parts.size !in 3..4 || parts.last() != "config") return DiscoveryResult.NotDiscovery
        val component = parts[0]
        val nodeId = if (parts.size == 4) parts[1] else null
        val objectId = parts[parts.size - 2]
        if (payload.isBlank()) return DiscoveryResult.Removed

        val raw = try {
            Json.parseToJsonElement(payload) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return DiscoveryResult.Skipped("payload is not a JSON object")
        val config = Abbreviations.expand(raw)

        if (component == "device") return parseDeviceBased(config, objectId)
        return when (val built = build(component, expandBaseTopic(config), nodeId, objectId)) {
            is Built.Ok -> DiscoveryResult.Entities(listOf(built.item))
            is Built.Bad -> DiscoveryResult.Skipped(built.reason)
        }
    }

    private fun parseDeviceBased(config: JsonObject, objectId: String): DiscoveryResult {
        val components = config["components"] as? JsonObject
            ?: return DiscoveryResult.Skipped("device discovery without 'cmps'")
        val shared = config - "components"
        val items = mutableListOf<DiscoveredEntity>()
        val skipped = mutableListOf<String>()
        for ((componentId, element) in components) {
            val c = element as? JsonObject
            if (c == null) {
                skipped += "$componentId: not an object"
                continue
            }
            val platform = (c["platform"] as? JsonPrimitive)?.contentOrNull
            if (platform == null) {
                skipped += "$componentId: missing platform"
                continue
            }
            if (c.keys == setOf("platform")) continue // removal marker for this component
            val merged = expandBaseTopic(JsonObject(shared + c - "platform"))
            when (val built = build(platform, merged, objectId, componentId)) {
                is Built.Ok -> items += built.item
                is Built.Bad -> skipped += "$componentId: ${built.reason}"
            }
        }
        return if (items.isEmpty() && skipped.isNotEmpty()) DiscoveryResult.Skipped(skipped.joinToString("; "))
        else DiscoveryResult.Entities(items, skipped)
    }

    private fun build(component: String, config: JsonObject, nodeId: String?, objectId: String): Built {
        val kind = KINDS[component] ?: return Built.Bad("unsupported component '$component'")
        val b = MqttBindings(component, config)
        val entityCategory = b.str("entity_category")
        if (entityCategory == "diagnostic" || entityCategory == "config") {
            return Built.Bad("hidden: entity_category '$entityCategory'")
        }
        if (!b.bool("enabled_by_default", true)) return Built.Bad("hidden: disabled by default")
        if (kind == Kind.LIGHT) {
            val schema = b.str("schema") ?: "default"
            if (schema !in LIGHT_SCHEMAS) return Built.Bad("light schema '$schema' unsupported")
        }
        if (kind in REQUIRES_COMMAND && b.str("command_topic") == null) return Built.Bad("missing command_topic")
        if (kind in REQUIRES_STATE && b.str("state_topic") == null) return Built.Bad("missing state_topic")
        if (kind == Kind.SELECT && b.strList("options").isNullOrEmpty()) return Built.Bad("select without options")

        val device = parseDevice(config["device"])
        val rawDeviceName = (config["device"] as? JsonObject)?.let { MqttBindings("device", it).str("name") }
        val uniqueId = b.str("unique_id") ?: listOfNotNull(component, nodeId, objectId).joinToString("_")
        val entity = Entity(
            uniqueId = uniqueId,
            deviceId = device?.id,
            name = displayName(b.str("name"), device, rawDeviceName, objectId),
            kind = kind,
            deviceClass = b.str("device_class"),
            unit = b.str("unit_of_measurement"),
            icon = b.str("icon"),
            capabilities = capabilities(kind, b),
            bindings = b,
        )
        return Built.Ok(DiscoveredEntity(entity, device))
    }

    private fun displayName(entityName: String?, device: Device?, rawDeviceName: String?, objectId: String): String {
        // An entity name that repeats the full device path gets the short device name instead.
        val name = if (entityName != null && device != null && rawDeviceName != null && rawDeviceName != device.name && entityName.startsWith(rawDeviceName)) {
            device.name + entityName.removePrefix(rawDeviceName)
        } else {
            entityName
        }
        return when {
            name == null -> device?.name ?: objectId
            device != null && !name.startsWith(device.name) -> "${device.name} $name"
            else -> name
        }
    }

    private fun parseDevice(element: JsonElement?): Device? {
        val o = element as? JsonObject ?: return null
        val d = MqttBindings("device", o)
        val ids = when (val i = o["identifiers"]) {
            is JsonArray -> i.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(i.contentOrNull)
            else -> emptyList()
        }
        val connections = (o["connections"] as? JsonArray).orEmpty().mapNotNull { pair ->
            (pair as? JsonArray)?.joinToString(":") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
        }
        val id = ids.firstOrNull() ?: connections.firstOrNull() ?: return null
        val path = DevicePath.split(d.str("name") ?: id)
        return Device(id, path.name, d.str("manufacturer"), d.str("model"), d.str("suggested_area"), pathArea = path.area)
    }

    private fun capabilities(kind: Kind, b: MqttBindings): Capabilities = when (kind) {
        Kind.LIGHT -> if (b.str("schema") == "json") {
            Capabilities(brightness = b.bool("brightness", false), colorModes = b.strList("supported_color_modes").orEmpty().toSet())
        } else {
            Capabilities(
                brightness = b.str("brightness_command_topic") != null,
                colorModes = if (b.str("rgb_command_topic") != null) setOf("rgb") else emptySet(),
            )
        }
        Kind.COVER -> Capabilities(position = b.str("set_position_topic") != null)
        Kind.CLIMATE -> Capabilities(
            hvacModes = ClimateMapper.modes(b),
            minTemp = b.double("min_temp") ?: ClimateMapper.DEFAULT_MIN_TEMP,
            maxTemp = b.double("max_temp") ?: ClimateMapper.DEFAULT_MAX_TEMP,
            tempStep = b.double("temp_step") ?: ClimateMapper.DEFAULT_TEMP_STEP,
        )
        Kind.NUMBER -> Capabilities(
            min = b.double("min") ?: NumberMapper.DEFAULT_MIN,
            max = b.double("max") ?: NumberMapper.DEFAULT_MAX,
            step = b.double("step") ?: NumberMapper.DEFAULT_STEP,
        )
        Kind.SELECT -> Capabilities(options = b.strList("options").orEmpty())
        Kind.ALARM_PANEL -> Capabilities(
            alarmModes = AlarmPanelMapper.supportedModes(b),
            codeArmRequired = AlarmPanelMapper.codeRequired(b, arming = true),
        )
        else -> Capabilities()
    }

    /** Resolves `~` in `*_topic` keys and `availability[].topic`, then drops `~`. */
    private fun expandBaseTopic(config: JsonObject): JsonObject {
        val base = (config["~"] as? JsonPrimitive)?.contentOrNull ?: return config
        fun fix(v: JsonElement): JsonElement {
            val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return v
            return when {
                s.startsWith("~") -> JsonPrimitive(base + s.substring(1))
                s.endsWith("~") -> JsonPrimitive(s.dropLast(1) + base)
                else -> v
            }
        }
        return JsonObject(
            (config - "~").mapValues { (key, value) ->
                when {
                    key.endsWith("_topic") -> fix(value)
                    key == "availability" && value is JsonArray -> JsonArray(
                        value.map { item ->
                            (item as? JsonObject)?.let { o -> JsonObject(o.mapValues { (k, v) -> if (k == "topic") fix(v) else v }) } ?: item
                        },
                    )
                    else -> value
                }
            },
        )
    }
}
