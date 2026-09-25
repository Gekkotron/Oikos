package com.gekkotron.oikos.transport

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Is the local broker reachable right now? (parent spec §4.5 step 1). */
fun interface LocalProbe {
    suspend fun reachable(): Boolean
}

/** A bare TCP connect to the broker's port, no MQTT. Reads no SSID, so it needs no location permission. */
class TcpProbe(
    private val host: String,
    private val port: Int,
    private val timeout: Duration = 1500.milliseconds,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LocalProbe {
    override suspend fun reachable(): Boolean = withTimeoutOrNull(timeout) {
        runInterruptible(io) {
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), timeout.inWholeMilliseconds.toInt()) }
                true
            } catch (e: IOException) {
                false
            } catch (e: IllegalArgumentException) {
                false // a port outside 0..65535
            }
        }
    } ?: false
}
