package com.gekkotron.oikos.transport

/** What [ActiveTransport] should run (spec §2; gateway spec §3.1). */
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

    /** Athena-HttpMqttGateway. [key] (AES-256) and [password] are secrets; [toString] masks them. */
    class Gateway(
        val url: String,
        key: ByteArray,
        val brokerHost: String?,
        val username: String?,
        val password: String?,
    ) : TransportSpec {
        private val keyBytes = key.copyOf()
        val key: ByteArray get() = keyBytes.copyOf()

        override fun equals(other: Any?): Boolean =
            other is Gateway && url == other.url && brokerHost == other.brokerHost && username == other.username &&
                password == other.password && keyBytes.contentEquals(other.keyBytes)

        override fun hashCode(): Int = listOf(url, brokerHost, username, password).hashCode() * 31 + keyBytes.contentHashCode()

        override fun toString(): String =
            "Gateway($url, brokerHost=$brokerHost, user=$username, password=${if (password == null) "none" else "***"}, key=***)"
    }

    /** A profile with both routes; [TransportSelector] picks one (gateway spec §3.5). */
    data class Routed(val direct: Direct, val gateway: Gateway, val route: Route) : TransportSpec
}

/** The user's route choice for a [TransportSpec.Routed] profile. */
enum class Route { AUTO, DIRECT, GATEWAY }
