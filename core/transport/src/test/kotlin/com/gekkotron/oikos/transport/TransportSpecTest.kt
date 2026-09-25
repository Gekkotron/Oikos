package com.gekkotron.oikos.transport

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class TransportSpecTest {
    private val key = ByteArray(32) { it.toByte() }
    private fun gateway(k: ByteArray = key) = TransportSpec.Gateway("https://gw.example", k, "192.168.1.91", "oikos", "hunter2")

    @Test
    fun `gateway specs compare by content, including the key`() {
        assertEquals(gateway(), gateway(key.copyOf()))
        assertEquals(gateway().hashCode(), gateway(key.copyOf()).hashCode())
        assertNotEquals(gateway(), gateway(ByteArray(32)))
    }

    @Test
    fun `gateway key is a defensive copy`() {
        val g = gateway()
        g.key[0] = 99
        assertEquals(0, g.key[0])
    }

    @Test
    fun `no secret appears in toString, even inside a routed spec`() {
        val direct = TransportSpec.Direct("h", 1883, tls = false, ws = false, username = "oikos", password = "hunter2", clientId = "c")
        val text = TransportSpec.Routed(direct, gateway(), Route.AUTO).toString()
        assertFalse("hunter2" in text, text)
        assertFalse(key.joinToString("") { "%02x".format(it) } in text, text)
    }
}
