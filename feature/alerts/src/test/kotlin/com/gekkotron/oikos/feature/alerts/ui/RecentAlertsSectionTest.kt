package com.gekkotron.oikos.feature.alerts.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.demo.DemoAlerts
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class RecentAlertsSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private val alerts = DemoAlerts.all(now).take(3)
    private val opened = mutableListOf<String>()
    private var seeAll = 0
    private var turnedOn = 0

    private fun show(alerts: List<com.gekkotron.oikos.alerts.Alert> = this.alerts, off: Boolean = false) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) {
            RecentAlertsSection(alerts, now, off, onTurnOn = { turnedOn++ }, onOpen = { opened += it }, onSeeAll = { seeAll++ })
        }
    }

    @Test
    fun `the title, See all and the rows`() {
        show()
        compose.onNodeWithText("Recent alerts").assertExists()
        compose.onNodeWithText("See all").assertExists()
        compose.onNodeWithText("Front door opened").assertExists()
        compose.onNodeWithText("Person in the garden").assertExists()
        compose.onNodeWithText("Alarm triggered").assertExists()
    }

    @Test
    fun `See all and a row report`() {
        show()
        compose.onNodeWithText("See all").performClick()
        compose.onNodeWithText("Person in the garden").performClick()
        assertEquals(1, seeAll)
        assertEquals(listOf("demo-camera"), opened)
    }

    @Test
    fun `no alerts shows the empty state`() {
        show(alerts = emptyList())
        compose.onNodeWithText("No alerts yet").assertExists()
    }

    @Test
    fun `the banner shows above the rows only while notifications are off`() {
        show(off = true)
        compose.onNodeWithText("Notifications are off").assertExists()
        compose.onNodeWithText("Turn on").performClick()
        assertEquals(1, turnedOn)
    }

    @Test
    fun `no banner while notifications are on`() {
        show(off = false)
        compose.onNodeWithText("Notifications are off").assertDoesNotExist()
    }

    @Test
    fun `the banner also shows with no alerts`() {
        show(alerts = emptyList(), off = true)
        compose.onNodeWithText("Notifications are off").assertExists()
        compose.onNodeWithText("No alerts yet").assertExists()
    }
}
