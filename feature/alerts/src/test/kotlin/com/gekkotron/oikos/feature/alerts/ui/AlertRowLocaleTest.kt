package com.gekkotron.oikos.feature.alerts.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.alerts.AlertLoc
import com.gekkotron.oikos.alerts.Severity
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

/** Localized alerts spec §5.4: the row writes a Frigate alert in the app's language. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "fr-w411dp-h914dp")
class AlertRowLocaleTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a frigate alert reads in french`() {
        val alert = Alert(
            "a1", AlertChannel.CAMERA, Severity.INFO, "Driveway", "Car in Driveway", null, null, null, emptyList(), "frigate",
            false, false, Instant.EPOCH, Instant.EPOCH, AlertLoc("frigate.object", mapOf("label" to "car", "zone" to "Driveway")),
        )
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { AlertRow(alert, now = Instant.EPOCH, onClick = {}) } }
        compose.onNodeWithText("Voiture dans la zone Driveway").assertExists()
        compose.onNodeWithText("Car in Driveway").assertDoesNotExist()
    }
}
