package com.gekkotron.oikos.data

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalHostsTest {
    @Test
    fun `private IPv4 and local names are local`() {
        listOf("192.168.1.91", "10.0.0.2", "172.16.0.1", "172.31.255.255", "broker.local", "NAS.lan", "broker.local.").forEach {
            assertTrue(isLikelyLocalHost(it), it)
        }
    }

    @Test
    fun `public and malformed hosts are not local`() {
        listOf("8.8.8.8", "172.32.0.1", "example.com", "192.168.1", "192.168.1.300", "").forEach {
            assertFalse(isLikelyLocalHost(it), it)
        }
    }
}
