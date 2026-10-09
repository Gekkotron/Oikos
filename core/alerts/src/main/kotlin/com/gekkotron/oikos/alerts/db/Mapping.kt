package com.gekkotron.oikos.alerts.db

import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.alerts.AlertAction
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.alerts.AlertLoc
import com.gekkotron.oikos.alerts.AlertParser
import com.gekkotron.oikos.alerts.Severity
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

internal fun Alert.toEntity(): AlertEntity = AlertEntity(
    id = id, channel = channel.id, severity = severity.id, title = title, body = body, imageUrl = imageUrl, videoUrl = videoUrl,
    entityId = entityId, actionsJson = actions.toJson(), source = source, silent = silent, resolved = resolved,
    sentAt = sentAt.toEpochMilli(), receivedAt = receivedAt.toEpochMilli(), locJson = loc?.toJson(),
)

internal fun AlertEntity.toAlert(): Alert = Alert(
    id = id, channel = AlertChannel.fromId(channel), severity = Severity.fromId(severity), title = title, body = body,
    imageUrl = imageUrl, videoUrl = videoUrl, entityId = entityId, actions = AlertParser.parseActions(actionsJson), source = source,
    silent = silent, resolved = resolved, sentAt = Instant.ofEpochMilli(sentAt), receivedAt = Instant.ofEpochMilli(receivedAt),
    loc = AlertParser.parseLoc(locJson),
)

private fun List<AlertAction>.toJson(): String = buildJsonArray {
    forEach { action ->
        add(
            buildJsonObject {
                put("id", action.id)
                put("label", action.label)
            },
        )
    }
}.toString()

internal fun AlertLoc.toJson(): String = buildJsonObject {
    put("key", key)
    put("args", buildJsonObject { args.forEach { (name, value) -> put(name, value) } })
}.toString()
