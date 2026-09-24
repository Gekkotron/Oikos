package com.gekkotron.oikos.data

/** True when [host] is clearly on the home network: a private IPv4 literal or a `.local`/`.lan` name (spec §4.5). */
fun isLikelyLocalHost(host: String): Boolean {
    val h = host.trim().lowercase().removeSuffix(".")
    if (h.endsWith(".local") || h.endsWith(".lan")) return true
    val parts = h.split('.').map { it.toIntOrNull() ?: return false }
    if (parts.size != 4 || parts.any { it !in 0..255 }) return false
    val (a, b) = parts
    return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
}
