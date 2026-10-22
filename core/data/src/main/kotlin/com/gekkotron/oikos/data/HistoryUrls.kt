package com.gekkotron.oikos.data

// The literal `]` is escaped: Android's ICU regex engine rejects it bare, unlike the JVM's.
private val HISTORY_URL = Regex("""^https?://([A-Za-z0-9._-]+|\[[0-9A-Fa-f:]+\])(?::(\d{1,5}))?/?$""")

/** `http(s)://host[:port]`, nothing after the host but an optional `/` (history spec §4.4). */
fun isHistoryUrl(text: String): Boolean {
    val m = HISTORY_URL.matchEntire(text.trim()) ?: return false
    val port = m.groupValues[2]
    return port.isEmpty() || port.toInt() in 1..65535
}

fun normalizeHistoryUrl(text: String): String? = text.trim().takeIf(::isHistoryUrl)?.removeSuffix("/")

const val DEFAULT_HISTORY_PORT = 8081

/** Where the hub serves history by default: next to the broker, on [DEFAULT_HISTORY_PORT]. */
fun defaultHistoryUrl(brokerHost: String): String {
    val host = if (':' in brokerHost && !brokerHost.startsWith("[")) "[$brokerHost]" else brokerHost
    return "http://$host:$DEFAULT_HISTORY_PORT"
}

/** The history service to ask: the [ConnectionProfile.Direct.historyUrl] override, else the one next to the broker. */
fun ConnectionProfile.Direct.effectiveHistoryUrl(): String = historyUrl ?: defaultHistoryUrl(local.host)
