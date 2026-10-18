package com.gekkotron.oikos.source.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Expands HA MQTT discovery abbreviations (table embedded as a resource, spec §4.3). */
internal object Abbreviations {
    private val tables: Map<String, Map<String, String>> by lazy {
        val text = checkNotNull(Abbreviations::class.java.getResourceAsStream("/com/gekkotron/oikos/source/abbreviations.json")) {
            "abbreviations.json missing from resources"
        }.use { it.readBytes().decodeToString() }
        Json.parseToJsonElement(text).jsonObject.mapValues { (_, table) ->
            table.jsonObject.mapValues { it.value.jsonPrimitive.content }
        }
    }

    fun expand(raw: JsonObject): JsonObject {
        val main = tables.getValue("payload")
        return JsonObject(
            rename(raw, main).mapValues { (key, value) ->
                when (key) {
                    "device" -> (value as? JsonObject)?.let { rename(it, tables.getValue("device")) } ?: value
                    "origin" -> (value as? JsonObject)?.let { rename(it, tables.getValue("origin")) } ?: value
                    "availability" -> (value as? JsonArray)
                        ?.let { list -> JsonArray(list.map { item -> (item as? JsonObject)?.let { rename(it, main) } ?: item }) }
                        ?: value
                    "components" -> (value as? JsonObject)
                        ?.let { cmps -> JsonObject(cmps.mapValues { (_, c) -> (c as? JsonObject)?.let(::expand) ?: c }) }
                        ?: value
                    else -> value
                }
            },
        )
    }

    /** Full key names win over abbreviations when both are present. */
    private fun rename(o: JsonObject, table: Map<String, String>): JsonObject = JsonObject(
        buildMap {
            for ((k, v) in o) {
                val full = table[k] ?: k
                if (k == full || full !in this) put(full, v)
            }
        },
    )
}
