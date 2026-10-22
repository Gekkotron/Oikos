package com.gekkotron.oikos.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryUrlsTest {
    @Test
    fun `http and https hosts with an optional port are history urls`() {
        assertTrue(isHistoryUrl("http://192.168.1.91:8081"))
        assertTrue(isHistoryUrl("https://history.lan"))
        assertTrue(isHistoryUrl("http://geekom.local:8081/"))
    }

    @Test
    fun `anything else is not`() {
        listOf("", "192.168.1.91:8081", "ftp://x", "http://", "http://h:0", "http://h:70000", "http://h/api", "http://h?x=1", "http://h#f", "http://a b")
            .forEach { assertFalse(isHistoryUrl(it), it) }
    }

    @Test
    fun `normalising trims and drops the trailing slash`() {
        assertEquals("http://192.168.1.91:8081", normalizeHistoryUrl("  http://192.168.1.91:8081/ "))
        assertNull(normalizeHistoryUrl("http://h/api"))
    }
}

class EffectiveHistoryUrlTest {
    @Test
    fun `the url is derived from the broker host`() {
        assertEquals("http://192.168.1.91:8081", ConnectionProfile.Direct(LocalBroker("192.168.1.91")).effectiveHistoryUrl())
    }

    @Test
    fun `an override wins`() {
        assertEquals("http://nas:9000", ConnectionProfile.Direct(LocalBroker("192.168.1.91"), historyUrl = "http://nas:9000").effectiveHistoryUrl())
    }

    @Test
    fun `an IPv6 literal host is bracketed`() {
        assertEquals("http://[fd00::1]:8081", ConnectionProfile.Direct(LocalBroker("fd00::1")).effectiveHistoryUrl())
    }
}
