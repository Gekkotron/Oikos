package com.gekkotron.oikos.alerts.text

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.alerts.AlertLoc
import com.gekkotron.oikos.alerts.Severity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class AlertTextTest {
    private val res: Resources get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    private fun alert(loc: AlertLoc?, body: String = "Server body") = Alert(
        "a1", AlertChannel.CAMERA, Severity.INFO, "Driveway", body, null, null, null, emptyList(), "frigate",
        false, false, Instant.EPOCH, Instant.EPOCH, loc,
    )

    private fun frigate(vararg args: Pair<String, String>) = alert(AlertLoc("frigate.object", mapOf(*args)))

    @Test
    fun `english sentences for every shape`() {
        assertEquals("Car detected", AlertText.body(frigate("label" to "car"), res))
        assertEquals("Car in Driveway", AlertText.body(frigate("label" to "car", "zone" to "Driveway"), res))
        assertEquals("Julien (person)", AlertText.body(frigate("label" to "person", "who" to "Julien"), res))
        assertEquals("Julien (person) in Driveway", AlertText.body(frigate("label" to "person", "who" to "Julien", "zone" to "Driveway"), res))
    }

    @Test
    @Config(qualifiers = "fr")
    fun `french sentences agree with the noun`() {
        assertEquals("Voiture détectée", AlertText.body(frigate("label" to "car"), res))
        assertEquals("Camion détecté", AlertText.body(frigate("label" to "truck"), res))
        assertEquals("Voiture dans la zone Driveway", AlertText.body(frigate("label" to "car", "zone" to "Driveway"), res))
        assertEquals("Julien (personne)", AlertText.body(frigate("label" to "person", "who" to "Julien"), res))
        assertEquals("Julien (personne) dans la zone Driveway", AlertText.body(frigate("label" to "person", "who" to "Julien", "zone" to "Driveway"), res))
    }

    @Test
    fun `unknown or differently-cased labels fall back to the body`() {
        assertEquals("Server body", AlertText.body(frigate("label" to "fire_hydrant"), res))
        assertEquals("Server body", AlertText.body(frigate("label" to "Car"), res))
        assertEquals("Server body", AlertText.body(frigate("zone" to "Driveway"), res))
    }

    @Test
    fun `no loc or an unknown key falls back to the body`() {
        assertEquals("Server body", AlertText.body(alert(null), res))
        assertEquals("Server body", AlertText.body(alert(AlertLoc("jeedom.door", mapOf("label" to "car"))), res))
    }

    @Test
    fun `every label has both strings in both languages`() {
        val labels = listOf(
            "person", "car", "truck", "bus", "motorcycle", "bicycle", "dog", "cat", "bird", "horse",
            "package", "face", "license_plate", "amazon", "dhl", "dpd", "fedex", "gls", "ups", "usps",
        )
        assertEquals(labels.toSet(), AlertText.FRIGATE_LABELS.keys)
        for (label in labels) {
            assert(AlertText.body(frigate("label" to label), res) != "Server body") { label }
        }
    }

    @Test
    @Config(qualifiers = "fr")
    fun `every label reads in french`() {
        for (label in AlertText.FRIGATE_LABELS.keys) {
            val text = AlertText.body(frigate("label" to label), res)
            assert(text.contains("détecté")) { "$label → $text" }
        }
    }
}
