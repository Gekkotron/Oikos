package com.gekkotron.oikos.feature.settings.presentation

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.FrigateConfig
import com.gekkotron.oikos.data.GatewayConfig
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.data.ProfileState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionSummaryTest {
    private val key = ByteArray(GatewayConfig.KEY_BYTES) { 0x5a }
    private val direct = ConnectionProfile.Direct(
        LocalBroker("192.168.1.91", 8883, tls = true, username = "oikos", password = "hunter2-secret"),
        prefix = "homeassistant",
        gateway = GatewayConfig("https://gw.example", key, "192.168.1.91"),
    )

    @Test
    fun `direct profile with a gateway`() {
        assertEquals(
            ConnectionSummary.Direct("192.168.1.91:8883", tls = true, ws = false, username = "oikos", prefix = "homeassistant", gatewayUrl = "https://gw.example", historyUrl = null, historyDefault = "http://192.168.1.91:8081"),
            ConnectionSummary.from(ProfileState.Ready(direct)),
        )
    }

    @Test
    fun `direct profile without a gateway or user`() {
        val plain = ConnectionProfile.Direct(LocalBroker("mqtt.local"))
        assertEquals(
            ConnectionSummary.Direct("mqtt.local:1883", tls = false, ws = false, username = null, prefix = "homeassistant", gatewayUrl = null, historyUrl = null, historyDefault = "http://mqtt.local:8081"),
            ConnectionSummary.from(ProfileState.Ready(plain)),
        )
    }

    @Test
    fun `demo profile`() {
        assertEquals(ConnectionSummary.Demo, ConnectionSummary.from(ProfileState.Ready(ConnectionProfile.Demo)))
    }

    @Test
    fun `no profile, locked or loading has no summary`() {
        assertNull(ConnectionSummary.from(ProfileState.None))
        assertNull(ConnectionSummary.from(ProfileState.Locked))
        assertNull(ConnectionSummary.from(ProfileState.Loading))
    }

    @Test
    fun `the summary carries neither the password nor the key`() {
        val text = ConnectionSummary.from(ProfileState.Ready(direct)).toString()
        assertFalse("hunter2-secret" in text)
        assertFalse(key.joinToString("") { "%02x".format(it) } in text)
        assertFalse("5a5a" in text.lowercase())
    }

    @Test
    fun `the history override and the derived default are both carried`() {
        val s = ConnectionSummary.from(ProfileState.Ready(ConnectionProfile.Direct(LocalBroker("h"), historyUrl = "http://h:8081"))) as ConnectionSummary.Direct
        assertEquals("http://h:8081", s.historyUrl)
        assertEquals("http://h:8081", s.historyDefault)
        val other = ConnectionSummary.from(ProfileState.Ready(ConnectionProfile.Direct(LocalBroker("h"), historyUrl = "http://nas:9000"))) as ConnectionSummary.Direct
        assertEquals("http://nas:9000", other.historyUrl)
        assertEquals("http://h:8081", other.historyDefault)
    }

    @Test
    fun `frigate shows url and user, and only whether a password exists`() {
        val s = ConnectionSummary.from(
            ProfileState.Ready(ConnectionProfile.Direct(LocalBroker("h"), frigate = FrigateConfig("http://f:8971", "admin", "fr1gate-secret"))),
        ) as ConnectionSummary.Direct
        assertEquals("http://f:8971", s.frigateUrl)
        assertEquals("admin", s.frigateUser)
        assertTrue(s.frigateHasPassword)
        assertFalse("fr1gate-secret" in s.toString())
    }
}
