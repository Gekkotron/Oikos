package com.gekkotron.oikos.data

import kotlinx.serialization.Serializable

const val DEFAULT_PREFIX = "homeassistant"

/** The local broker. [password] is a secret; [toString] masks it. */
data class LocalBroker(
    val host: String,
    val port: Int = 1883,
    val tls: Boolean = false,
    val ws: Boolean = false,
    val username: String? = null,
    val password: String? = null,
) {
    override fun toString(): String =
        "LocalBroker($host:$port, tls=$tls, ws=$ws, user=$username, password=${if (password == null) "none" else "***"})"
}

/** Remote gateway settings from the QR code. [key] is a secret. */
class GatewayConfig(val url: String, key: ByteArray, val brokerHost: String?) {
    private val keyBytes = key.copyOf()
    val key: ByteArray get() = keyBytes.copyOf()

    init {
        require(keyBytes.size == KEY_BYTES) { "gateway key must be $KEY_BYTES bytes" }
    }

    override fun equals(other: Any?): Boolean =
        other is GatewayConfig && url == other.url && brokerHost == other.brokerHost && keyBytes.contentEquals(other.keyBytes)

    override fun hashCode(): Int = (url.hashCode() * 31 + (brokerHost?.hashCode() ?: 0)) * 31 + keyBytes.contentHashCode()

    override fun toString(): String = "GatewayConfig($url, brokerHost=$brokerHost, key=***)"

    companion object {
        const val KEY_BYTES = 32
    }
}

sealed interface ConnectionProfile {
    val prefix: String

    data object Demo : ConnectionProfile {
        override val prefix: String = DEFAULT_PREFIX
    }

    data class Direct(
        val local: LocalBroker,
        override val prefix: String = DEFAULT_PREFIX,
        val gateway: GatewayConfig? = null,
        val pushTokenTopic: String? = null,
        /** The OikosHistory service, e.g. `http://192.168.1.91:8090`; null hides history (history spec §4.4). */
        val historyUrl: String? = null,
    ) : ConnectionProfile
}

sealed interface ProfileState {
    data object Loading : ProfileState
    data object None : ProfileState

    /** A profile exists but its secrets could not be decrypted, for example after a restore to a new phone. */
    data object Locked : ProfileState
    /**
     * [roomsPending]: show the Rooms step before Home (a direct profile that was just set up). [notificationsPending]: then the
     * notification step (push alerts spec §7.5).
     */
    data class Ready(
        val profile: ConnectionProfile,
        val roomsPending: Boolean = false,
        val notificationsPending: Boolean = false,
    ) : ProfileState
}

/**
 * How to reach a profile that has a gateway (gateway spec §4). Kept out of [ConnectionProfile]: changing
 * it rebuilds the transport but never the discovery source, so the house on screen survives.
 */
@Serializable
enum class RoutePreference { AUTO, DIRECT, GATEWAY }
