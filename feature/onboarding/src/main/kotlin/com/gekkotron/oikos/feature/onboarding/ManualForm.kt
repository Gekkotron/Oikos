package com.gekkotron.oikos.feature.onboarding

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.DEFAULT_PREFIX
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.data.isLikelyLocalHost

enum class FormField { HOST, PORT, PREFIX }

/** The manual-entry fields as typed (spec §5 step 3). [password] is a secret; [toString] masks it. */
data class ManualForm(
    val host: String = "",
    val port: String = "1883",
    val tls: Boolean = false,
    val ws: Boolean = false,
    val username: String = "",
    val password: String = "",
    val prefix: String = DEFAULT_PREFIX,
) {
    fun errors(): Set<FormField> = buildSet {
        val h = host.trim()
        // Host only: users often paste "mqtt://host:1883". IPv6 literals are out of scope for v1.
        if (h.isEmpty() || h.any { it.isWhitespace() || it == ':' || it == '/' }) add(FormField.HOST)
        if ((port.trim().toIntOrNull() ?: 0) !in 1..65535) add(FormField.PORT)
        if (prefix.isBlank()) add(FormField.PREFIX)
    }

    /** Plain TCP to a host that doesn't look local (spec §4.5): warn, don't block. */
    val plainTextWarning: Boolean get() = !tls && host.isNotBlank() && !isLikelyLocalHost(host)

    /** Only valid when [errors] is empty. */
    fun toProfile(): ConnectionProfile.Direct = ConnectionProfile.Direct(
        LocalBroker(
            host = host.trim(),
            port = port.trim().toInt(),
            tls = tls,
            ws = ws,
            username = username.trim().ifEmpty { null },
            password = password.ifEmpty { null },
        ),
        prefix = prefix.trim(),
    )

    override fun toString(): String = "ManualForm($host:$port, tls=$tls, ws=$ws, user=$username, password=***, prefix=$prefix)"
}
