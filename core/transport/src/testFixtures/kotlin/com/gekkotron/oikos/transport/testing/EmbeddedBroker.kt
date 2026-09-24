package com.gekkotron.oikos.transport.testing

import com.hivemq.client.mqtt.mqtt3.Mqtt3BlockingClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client
import io.moquette.broker.Server
import io.moquette.broker.config.MemoryConfig
import io.moquette.broker.security.IAuthenticator
import java.net.ServerSocket
import java.util.Properties
import java.util.UUID

fun freePort(): Int = ServerSocket(0).use { it.localPort }

/** An in-process Moquette broker on 127.0.0.1 for transport tests. [stop] then [start] simulates a broker restart. */
class EmbeddedBroker(
    private val credentials: Pair<String, String>? = null,
    private val websocket: Boolean = false,
) : AutoCloseable {
    val port: Int = freePort()
    val wsPort: Int = freePort()
    private var server: Server? = null

    fun start() {
        val props = Properties().apply {
            setProperty("host", "127.0.0.1")
            setProperty("port", port.toString())
            setProperty("websocket_port", if (websocket) wsPort.toString() else "disabled")
            setProperty("allow_anonymous", (credentials == null).toString())
            setProperty("persistence_enabled", "false")
            setProperty("telemetry_enabled", "false")
        }
        val authenticator = credentials?.let { (user, pass) ->
            IAuthenticator { _, username, password -> username == user && password != null && String(password, Charsets.UTF_8) == pass }
        }
        server = Server().apply { startServer(MemoryConfig(props), emptyList(), null, authenticator, null) }
    }

    fun stop() {
        server?.stopServer()
        server = null
    }

    override fun close() = stop()

    /** A plain HiveMQ client acting as another device on the broker. Call `disconnect()` when done. */
    fun peer(): Mqtt3BlockingClient {
        var builder = Mqtt3Client.builder().identifier("peer-${UUID.randomUUID()}").serverHost("127.0.0.1").serverPort(port)
        if (credentials != null) {
            builder = builder.simpleAuth().username(credentials.first).password(credentials.second.encodeToByteArray()).applySimpleAuth()
        }
        return builder.buildBlocking().also { it.connect() }
    }
}
