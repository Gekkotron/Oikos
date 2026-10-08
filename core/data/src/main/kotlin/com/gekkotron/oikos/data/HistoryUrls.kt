package com.gekkotron.oikos.data

private val HISTORY_URL = Regex("""^https?://([A-Za-z0-9._-]+|\[[0-9A-Fa-f:]+])(?::(\d{1,5}))?/?$""")

/** `http(s)://host[:port]`, nothing after the host but an optional `/` (history spec §4.4). */
fun isHistoryUrl(text: String): Boolean {
    val m = HISTORY_URL.matchEntire(text.trim()) ?: return false
    val port = m.groupValues[2]
    return port.isEmpty() || port.toInt() in 1..65535
}

fun normalizeHistoryUrl(text: String): String? = text.trim().takeIf(::isHistoryUrl)?.removeSuffix("/")
