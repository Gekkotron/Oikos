package com.gekkotron.oikos.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryUrlsTest {
    @Test
    fun `http and https hosts with an optional port are history urls`() {
        assertTrue(isHistoryUrl("http://192.168.1.91:8090"))
        assertTrue(isHistoryUrl("https://history.lan"))
        assertTrue(isHistoryUrl("http://geekom.local:8090/"))
    }

    @Test
    fun `anything else is not`() {
        listOf("", "192.168.1.91:8090", "ftp://x", "http://", "http://h:0", "http://h:70000", "http://h/v1", "http://h?x=1", "http://h#f", "http://a b")
            .forEach { assertFalse(isHistoryUrl(it), it) }
    }

    @Test
    fun `normalising trims and drops the trailing slash`() {
        assertEquals("http://192.168.1.91:8090", normalizeHistoryUrl("  http://192.168.1.91:8090/ "))
        assertNull(normalizeHistoryUrl("http://h/v1"))
    }
}
