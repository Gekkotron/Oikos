package com.gekkotron.oikos.transport

import com.gekkotron.oikos.transport.testing.freePort
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class TcpProbeTest {
    @Test
    fun `a listening port is reachable`() = runBlocking {
        ServerSocket(0).use { server -> assertTrue(TcpProbe("127.0.0.1", server.localPort).reachable()) }
    }

    @Test
    fun `a closed port is not`() = runBlocking {
        assertFalse(TcpProbe("127.0.0.1", freePort()).reachable())
    }

    @Test
    fun `an unresolvable host is not`() = runBlocking {
        assertFalse(TcpProbe("no-such-host.invalid", 1883).reachable())
    }

    @Test
    fun `a connector stuck resolving DNS does not block reachable() past the timeout`() = runBlocking {
        // A faithful stand-in for getaddrinfo: real DNS resolution ignores thread interruption, unlike
        // Thread.sleep alone (which would just unblock early once runInterruptible interrupts the thread).
        val probe = TcpProbe(
            "127.0.0.1",
            1883,
            timeout = 200.milliseconds,
            connect = { _, _, _ ->
                val deadline = System.nanoTime() + 5_000_000_000L
                while (System.nanoTime() < deadline) {
                    try {
                        Thread.sleep(50)
                    } catch (e: InterruptedException) {
                        // swallowed on purpose, like getaddrinfo ignoring interrupts
                    }
                }
            },
        )
        val elapsedMs = measureTimeMillis { assertFalse(probe.reachable()) }
        assertTrue(elapsedMs < 3000, "reachable() took ${elapsedMs}ms, expected well under 3000ms")
    }

    @Test
    fun `the socket connect timeout is clamped to at least 1 ms`() {
        assertEquals(1, clampConnectTimeoutMillis(Duration.ZERO))
        assertEquals(1, clampConnectTimeoutMillis((-5).milliseconds))
        assertEquals(1500, clampConnectTimeoutMillis(1500.milliseconds))
    }
}
