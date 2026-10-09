package com.gekkotron.oikos.alerts.demo

import com.gekkotron.oikos.alerts.AlertChannel
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoAlertsTest {
    private val now = Instant.parse("2026-10-07T12:00:00Z")

    @Test
    fun `six alerts, one per main channel, newest first, with unique ids`() {
        val alerts = DemoAlerts.all(now)
        assertEquals(6, alerts.size)
        assertEquals(
            setOf(AlertChannel.ALARM, AlertChannel.SECURITY, AlertChannel.CAMERA, AlertChannel.ENERGY, AlertChannel.CLIMATE, AlertChannel.SYSTEM),
            alerts.map { it.channel }.toSet(),
        )
        assertEquals(6, alerts.map { it.id }.toSet().size)
        assertEquals(alerts.sortedByDescending { it.sentAt }, alerts)
        assertTrue(alerts.all { it.sentAt <= now })
    }

    @Test
    fun `pictures are local demo resources, never network urls, and there is no clip to fetch`() {
        val alerts = DemoAlerts.all(now)
        val pictures = alerts.mapNotNull { it.imageUrl }
        assertTrue(pictures.isNotEmpty() && pictures.all { it.startsWith("demo://") })
        assertTrue(alerts.all { it.videoUrl == null })
    }

    @Test
    fun `the set shows a resolved alert and an alert with action buttons`() {
        val alerts = DemoAlerts.all(now)
        assertTrue(alerts.any { it.resolved })
        assertTrue(alerts.any { it.actions.isNotEmpty() })
    }

    @Test
    fun `the same moment gives the same alerts`() {
        assertEquals(DemoAlerts.all(now), DemoAlerts.all(now))
    }

    @Test
    fun `the demo camera alert carries a frigate loc so the demo shows localized text`() {
        val camera = DemoAlerts.all(java.time.Instant.parse("2026-10-09T12:00:00Z")).single { it.id == "demo-camera" }
        kotlin.test.assertEquals(com.gekkotron.oikos.alerts.AlertLoc("frigate.object", mapOf("label" to "person", "zone" to "Garden")), camera.loc)
    }
}
