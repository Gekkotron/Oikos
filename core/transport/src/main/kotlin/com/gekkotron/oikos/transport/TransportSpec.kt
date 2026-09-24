package com.gekkotron.oikos.transport

/** What [ActiveTransport] should run (spec §2). Plan 2 adds `Gateway`. */
sealed interface TransportSpec {
    data object Demo : TransportSpec

    data class Direct(
        val host: String,
        val port: Int,
        val tls: Boolean,
        val ws: Boolean,
        val username: String?,
        val password: String?,
        val clientId: String,
    ) : TransportSpec {
        override fun toString(): String =
            "Direct($host:$port, tls=$tls, ws=$ws, user=$username, password=${if (password == null) "none" else "***"}, clientId=$clientId)"
    }
}
