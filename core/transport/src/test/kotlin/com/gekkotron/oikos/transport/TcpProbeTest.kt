package com.gekkotron.oikos.transport

import com.gekkotron.oikos.transport.testing.freePort
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
}
