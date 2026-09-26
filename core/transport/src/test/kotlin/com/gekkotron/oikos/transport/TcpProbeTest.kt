package com.gekkotron.oikos.transport

import com.gekkotron.oikos.transport.testing.freePort
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
    fun `a connector that throws something unexpected is unreachable, not an uncaught crash`() = runBlocking {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            val probe = TcpProbe("127.0.0.1", 1883, timeout = 5.seconds, connect = { _, _, _ -> throw IllegalStateException("boom") })
            val elapsedMs = measureTimeMillis { assertFalse(probe.reachable()) }
            assertTrue(elapsedMs < 2000, "the failure completes the probe at once, took ${elapsedMs}ms")
            Thread.sleep(100) // let a stray handler invocation on the IO thread land, if any
            assertTrue(uncaught.isEmpty(), "uncaught: $uncaught")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun `the socket connect timeout is clamped to at least 1 ms`() {
        assertEquals(1, clampConnectTimeoutMillis(Duration.ZERO))
        assertEquals(1, clampConnectTimeoutMillis((-5).milliseconds))
        assertEquals(1500, clampConnectTimeoutMillis(1500.milliseconds))
    }
}
