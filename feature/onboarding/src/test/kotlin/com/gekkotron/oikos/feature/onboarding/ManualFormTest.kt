package com.gekkotron.oikos.feature.onboarding

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.LocalBroker
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManualFormTest {
    private val valid = ManualForm(host = "192.168.1.91")

    @Test
    fun `a valid form has no errors`() = assertEquals(emptySet(), valid.errors())

    @Test
    fun `blank host is an error`() = assertEquals(setOf(FormField.HOST), valid.copy(host = "  ").errors())

    @Test
    fun `host with scheme or port is an error`() {
        listOf("mqtt://192.168.1.91", "192.168.1.91:1883", "broker local").forEach {
            assertEquals(setOf(FormField.HOST), valid.copy(host = it).errors(), it)
        }
    }

    @Test
    fun `port must be 1 to 65535`() {
        listOf("0", "65536", "abc", "").forEach { assertEquals(setOf(FormField.PORT), valid.copy(port = it).errors(), it) }
    }

    @Test
    fun `blank prefix is an error`() = assertEquals(setOf(FormField.PREFIX), valid.copy(prefix = " ").errors())

    @Test
    fun `toProfile trims and turns empty credentials into null`() {
        val p = ManualForm(host = " broker.local ", port = " 8883 ", tls = true, username = " ", password = "", prefix = " ha ").toProfile()
        assertEquals(ConnectionProfile.Direct(LocalBroker("broker.local", 8883, tls = true), prefix = "ha"), p)
    }

    @Test
    fun `plain TCP to a non-local host warns`() {
        assertTrue(valid.copy(host = "mqtt.example.com").plainTextWarning)
        assertFalse(valid.plainTextWarning)
        assertFalse(valid.copy(host = "mqtt.example.com", tls = true).plainTextWarning)
        assertFalse(valid.copy(host = "").plainTextWarning)
    }

    @Test
    fun `toString hides the password`() = assertFalse("s3cret" in valid.copy(password = "s3cret").toString())
}
