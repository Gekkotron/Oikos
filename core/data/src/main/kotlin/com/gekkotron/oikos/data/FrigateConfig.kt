package com.gekkotron.oikos.data

/** Frigate's address and optional login, for alert media (Frigate auth spec §3). [password] is a secret; [toString] masks it. */
data class FrigateConfig(val url: String, val username: String? = null, val password: String? = null) {
    /** A username and a password: the app logs in. Anything less: media is fetched without a cookie. */
    val hasLogin: Boolean get() = !username.isNullOrEmpty() && !password.isNullOrEmpty()

    override fun toString(): String = "FrigateConfig($url, user=$username, password=${if (password == null) "none" else "***"})"
}

/** The generic default shown in Settings: Frigate's authenticated port on a host named `frigate`. */
const val DEFAULT_FRIGATE_URL = "http://frigate:8971"

/** `http(s)://host[:port]` without a trailing slash, like the history URL (spec §3, §5); null when it is not one. */
fun normalizeFrigateUrl(text: String): String? = normalizeHistoryUrl(text)
