package com.gekkotron.oikos.state.mqtt

import com.gekkotron.oikos.model.Bindings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** The fully expanded HA discovery config of one entity (full key names, `~` resolved). */
data class MqttBindings(val component: String, val config: JsonObject) : Bindings {
    /** String option. JSON booleans become "True"/"False", like HA's cv.string. */
    fun str(key: String): String? {
        val p = config[key] as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        if (p.isString) return p.content
        return p.booleanOrNull?.let { if (it) "True" else "False" } ?: p.content
    }

    fun str(key: String, default: String): String = str(key) ?: default

    fun double(key: String): Double? =
        (config[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toDoubleOrNull()

    fun int(key: String, default: Int): Int = double(key)?.toInt() ?: default

    fun bool(key: String, default: Boolean): Boolean = (config[key] as? JsonPrimitive)?.booleanOrNull ?: default

    fun strList(key: String): List<String>? =
        (config[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.content }
}
