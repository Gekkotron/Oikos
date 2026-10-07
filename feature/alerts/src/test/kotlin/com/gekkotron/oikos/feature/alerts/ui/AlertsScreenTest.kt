package com.gekkotron.oikos.feature.alerts.ui

import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.alerts.demo.DemoAlerts
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.alerts.presentation.AlertsUi
import com.gekkotron.oikos.feature.alerts.presentation.groupByDay
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class AlertsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private val all = AlertsUi(null, groupByDay(DemoAlerts.all(now), ZoneOffset.UTC, LocalDate.of(2026, 10, 7)), loading = false)
    private val selected = mutableListOf<AlertChannel?>()
    private val opened = mutableListOf<String>()
    private var cleared = 0
    private var backs = 0

    private fun show(state: AlertsUi) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) {
            AlertsScreen(state, now, onSelect = { selected += it }, onOpen = { opened += it }, onClearAll = { cleared++ }, onBack = { backs++ })
        }
    }

    @Test
    fun `the title, the chips and the day headers`() {
        show(all)
        compose.onNodeWithText("Alerts").assertExists()
        listOf("All", "Alarm", "Security", "Cameras", "Energy", "Climate", "System", "Other").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("Today").assertExists()
        compose.onNode(hasText("Yesterday") and isHeading()).assertExists()
        // The third day is below the fold of the lazy list: scroll to it first.
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Today"))).performScrollToNode(hasText("Oct 5, 2026"))
        compose.onNodeWithText("Oct 5, 2026").assertExists()
    }

    @Test
    fun `a chip reports its channel and All reports null`() {
        show(all)
        compose.onNodeWithText("Cameras").performClick()
        compose.onNodeWithText("All").performClick()
        assertEquals(listOf<AlertChannel?>(AlertChannel.CAMERA, null), selected)
    }

    @Test
    fun `a row opens its alert`() {
        show(all)
        compose.onNodeWithText("Person in the garden").performClick()
        assertEquals(listOf("demo-camera"), opened)
    }

    @Test
    fun `no alerts at all and no alerts in a filter have their own words`() {
        show(AlertsUi(null, emptyList(), loading = false))
        compose.onNodeWithText("No alerts yet").assertExists()
    }

    @Test
    fun `an empty filter says no alerts in this category`() {
        show(AlertsUi(AlertChannel.MISC, emptyList(), loading = false))
        compose.onNodeWithText("No alerts in this category").assertExists()
    }

    @Test
    fun `while loading neither the empty state nor rows show`() {
        show(AlertsUi())
        compose.onNodeWithText("No alerts yet").assertDoesNotExist()
    }

    @Test
    fun `clear all asks first and only the confirm clears`() {
        show(all)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Clear all").performClick()
        compose.onNodeWithText("Clear all alerts?").assertExists()
        compose.onNodeWithText("This only clears this phone.").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, cleared)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Clear all").performClick()
        compose.onNodeWithText("Clear").performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun `the back arrow goes back`() {
        show(all)
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }
}
