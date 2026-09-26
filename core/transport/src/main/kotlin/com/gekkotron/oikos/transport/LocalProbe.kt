package com.gekkotron.oikos.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Is the local broker reachable right now? (parent spec §4.5 step 1). */
fun interface LocalProbe {
    suspend fun reachable(): Boolean
}

/** `0` means "wait forever" to [Socket.connect]; never pass that through as a probe timeout. */
internal fun clampConnectTimeoutMillis(timeout: Duration): Int = timeout.inWholeMilliseconds.toInt().coerceAtLeast(1)

/**
 * A bare TCP connect to the broker's port, no MQTT. Reads no SSID, so it needs no location permission.
 *
 * DNS resolution (inside [InetSocketAddress]) and [Socket.connect] itself both ignore thread interruption,
 * so the connect runs detached from the coroutine [reachable] waits on: cancelling that wait (on timeout)
 * cannot stop it. The stray blocking work is left to finish on its own, bounded by [timeout] on the socket
 * connect step but not on resolution, and it closes its own socket when it does.
 */
class TcpProbe(
    private val host: String,
    private val port: Int,
    private val timeout: Duration = 1500.milliseconds,
    io: CoroutineDispatcher = Dispatchers.IO,
    internal val connect: (String, Int, Int) -> Unit = { h, p, timeoutMillis ->
        Socket().use { it.connect(InetSocketAddress(h, p), timeoutMillis) }
    },
) : LocalProbe {
    private val detached = CoroutineScope(SupervisorJob() + io)

    override suspend fun reachable(): Boolean {
        val millis = clampConnectTimeoutMillis(timeout)
        val result = CompletableDeferred<Boolean>()
        detached.launch {
            result.complete(
                try {
                    connect(host, port, millis)
                    true
                } catch (e: Exception) {
                    // IOException, IllegalArgumentException (a port outside 0..65535), SecurityException…:
                    // anything thrown here would be uncaught (`detached` has no handler) and crash the process.
                    false
                },
            )
        }
        return withTimeoutOrNull(timeout) { result.await() } ?: false
    }
}
