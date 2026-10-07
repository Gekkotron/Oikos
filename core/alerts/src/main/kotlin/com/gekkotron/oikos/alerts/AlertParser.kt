package com.gekkotron.oikos.alerts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/** Turns the two shapes OikosPush sends (spec §2.4 FCM data, §3.5 history JSON) into [Alert]; null means malformed. */
object AlertParser {
    /** [data] is the FCM data map: every value a string. Needs `v == "1"` and an `id`; a title unless it is a cancel. */
    fun fromData(data: Map<String, String>, receivedAt: Instant = Instant.now()): Alert? {
        if (data["v"] != "1") return null
        val id = data["id"]?.takeIf { it.isNotBlank() } ?: return null
        val cancel = data["cancel"] == "true"
        val title = data["title"].orEmpty()
        if (!cancel && title.isBlank()) return null
        return Alert(
            id = id,
            channel = AlertChannel.fromId(data["channel"]),
            severity = Severity.fromId(data["severity"]),
            title = title,
            body = data["body"].orEmpty(),
            imageUrl = data["image_url"]?.takeIf { it.isNotBlank() },
            videoUrl = data["video_url"]?.takeIf { it.isNotBlank() },
            entityId = data["entity_id"]?.takeIf { it.isNotBlank() },
            actions = parseActions(data["actions"]),
            source = data["source"]?.takeIf { it.isNotBlank() },
            silent = data["silent"] == "true",
            resolved = cancel,
            sentAt = data["sent_at"]?.toLongOrNull()?.let(Instant::ofEpochSecond) ?: receivedAt,
            receivedAt = receivedAt,
        )
    }

    /** One entry of `oikos-push/history`: the same keys with typed values. [Alert.receivedAt] is its `sent_at`. */
    fun fromHistory(obj: JsonObject, now: Instant = Instant.now()): Alert? {
        val data = obj.mapNotNull { (key, value) ->
            when (value) {
                is JsonNull -> null
                is JsonPrimitive -> key to value.content
                else -> key to value.toString()
            }
        }.toMap()
        val sent = data["sent_at"]?.toLongOrNull()?.let(Instant::ofEpochSecond) ?: now
        return fromData(data, receivedAt = sent)
    }

    /** The whole retained history message; null when it is not a JSON array. Bad entries are skipped one by one. */
    fun parseHistory(payload: String, now: Instant = Instant.now()): List<Alert>? {
        val array = try {
            Json.parseToJsonElement(payload) as? JsonArray
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        return array.mapNotNull { (it as? JsonObject)?.let { entry -> fromHistory(entry, now) } }
    }

    /** `[{id,label}]` as text. Bad JSON means no actions, not a dropped alert. */
    internal fun parseActions(raw: String?): List<AlertAction> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = try {
            Json.parseToJsonElement(raw) as? JsonArray
        } catch (e: IllegalArgumentException) {
            null
        } ?: return emptyList()
        return array.mapNotNull(::actionOf)
    }

    private fun actionOf(element: JsonElement): AlertAction? {
        val obj = element as? JsonObject ?: return null
        val id = (obj["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return null
        val label = (obj["label"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: id
        return AlertAction(id, label)
    }
}
