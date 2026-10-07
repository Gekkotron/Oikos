package com.gekkotron.oikos.alerts.demo

import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.alerts.AlertAction
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.alerts.Severity
import java.time.Duration
import java.time.Instant

/** Six fixed alerts for the demo house, previews and screenshots (push alerts spec §7.6). Pictures are `demo://` resources. */
object DemoAlerts {
    const val FRONT_DOOR_IMAGE = "demo://front_door"
    const val GARDEN_IMAGE = "demo://garden"
    const val GARAGE_IMAGE = "demo://garage"

    fun all(now: Instant): List<Alert> = listOf(
        demo("demo-security", AlertChannel.SECURITY, Severity.WARNING, "Front door opened", "The entrance door opened while the alarm was armed.", Duration.ofMinutes(2), now, image = FRONT_DOOR_IMAGE),
        demo("demo-camera", AlertChannel.CAMERA, Severity.INFO, "Person in the garden", "A person was detected near the back fence.", Duration.ofMinutes(18), now, image = GARDEN_IMAGE),
        demo("demo-alarm", AlertChannel.ALARM, Severity.CRITICAL, "Alarm triggered", "The garage door was forced. The alarm has been disarmed since.", Duration.ofHours(1), now, image = GARAGE_IMAGE, resolved = true, actions = listOf(AlertAction("silence", "Silence the siren"), AlertAction("disarm", "Disarm"))),
        demo("demo-energy", AlertChannel.ENERGY, Severity.INFO, "Solar peak", "Production reached 3.2 kW, the best of the week.", Duration.ofHours(3), now),
        demo("demo-climate", AlertChannel.CLIMATE, Severity.WARNING, "The living room is warm", "The temperature is 26.4 °C.", Duration.ofHours(26), now),
        demo("demo-system", AlertChannel.SYSTEM, Severity.INFO, "Gateway reconnected", "The remote gateway is back after a short outage.", Duration.ofHours(50), now),
    )

    private fun demo(
        id: String,
        channel: AlertChannel,
        severity: Severity,
        title: String,
        body: String,
        ago: Duration,
        now: Instant,
        image: String? = null,
        resolved: Boolean = false,
        actions: List<AlertAction> = emptyList(),
    ): Alert {
        val sent = now.minus(ago)
        return Alert(id, channel, severity, title, body, image, null, null, actions, "demo", false, resolved, sent, sent)
    }
}
