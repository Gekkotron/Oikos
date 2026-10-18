package com.gekkotron.oikos.state.template

import kotlin.math.abs
import kotlin.math.floor

/** Python-style string form, used to compare rendered values with HA payload options. */
fun Any?.asPayloadString(): String? = when (this) {
    null -> null
    is Boolean -> if (this) "True" else "False"
    is String -> this
    else -> toString()
}

fun Any?.asDouble(): Double? = when (this) {
    is Number -> toDouble()
    is String -> trim().toDoubleOrNull()
    else -> null
}

/** Formats a number for MQTT payloads: integral values drop the ".0". */
fun formatNumber(d: Double): String =
    if (!d.isInfinite() && !d.isNaN() && d == floor(d) && abs(d) < 1e15) d.toLong().toString() else d.toString()
