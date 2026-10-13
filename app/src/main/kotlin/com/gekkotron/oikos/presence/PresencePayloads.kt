package com.gekkotron.oikos.presence

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray

enum class PresenceState { HOME, AWAY }

object PresenceTopics {
    fun discovery(alias: String): String = "homeassistant/device_tracker/oikos_${slug(alias)}/config"
    fun state(alias: String): String = "oikos/presence/${slug(alias)}/state"
    fun availability(alias: String): String = "oikos/presence/${slug(alias)}/available"

    internal fun slug(alias: String): String {
        val cleaned = alias.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
            .trim('_').replace(Regex("_+"), "_")
        return cleaned.ifEmpty { "phone" }
    }
}

object PresencePayloads {
    const val AVAILABLE_ONLINE = "online"
    const val AVAILABLE_OFFLINE = "offline"

    fun state(state: PresenceState): String = when (state) {
        PresenceState.HOME -> "home"
        PresenceState.AWAY -> "not_home"
    }

    /** HA MQTT Discovery `device_tracker` config (retained; HA keeps the latest). */
    fun discovery(alias: String): String {
        val slug = PresenceTopics.slug(alias)
        val displayAlias = alias.ifBlank { slug }
        val obj: JsonObject = buildJsonObject {
            put("name", "Oikos $displayAlias")
            put("state_topic", "oikos/presence/$slug/state")
            put("availability_topic", "oikos/presence/$slug/available")
            put("payload_home", "home")
            put("payload_not_home", "not_home")
            put("payload_available", AVAILABLE_ONLINE)
            put("payload_not_available", AVAILABLE_OFFLINE)
            put("source_type", "gps")
            put("unique_id", "oikos_presence_$slug")
            put("device", buildJsonObject {
                put("identifiers", buildJsonArray { add("oikos_$slug") })
                put("name", "Oikos $displayAlias")
                put("manufacturer", "Oikos")
                put("model", "Android")
            })
        }
        return obj.toString()
    }
}
