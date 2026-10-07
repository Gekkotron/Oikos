package com.gekkotron.oikos.feature.alerts.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.alerts.demo.DemoAlerts
import com.gekkotron.oikos.designsystem.OikosBackground
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.alerts.distinctColors
import com.gekkotron.oikos.feature.alerts.luminanceAt
import com.gekkotron.oikos.feature.alerts.presentation.AlertsUi
import com.gekkotron.oikos.feature.alerts.presentation.groupByDay
import com.gekkotron.oikos.feature.alerts.renderPng
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class AlertsScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private val today = LocalDate.of(2026, 10, 7)

    private fun render(theme: ThemeChoice, name: String, filter: AlertChannel?) = run {
        val alerts = DemoAlerts.all(now).filter { filter == null || it.channel == filter }
        val state = AlertsUi(filter, groupByDay(alerts, ZoneOffset.UTC, today), loading = false)
        compose.setContent {
            OikosTheme(theme) {
                OikosBackground { AlertsScreen(state, now, onSelect = {}, onOpen = {}, onClearAll = {}, onBack = {}) }
            }
        }
        compose.renderPng(name)
    }

    @Test
    fun `All in Night Glass`() {
        val bitmap = render(ThemeChoice.NIGHT_GLASS, "alerts-all-night", null)
        assertTrue(bitmap.distinctColors() > 8)
        assertTrue(bitmap.luminanceAt(4, 4) < 0.35f)
    }

    @Test
    fun `All in Warm Minimal`() {
        val bitmap = render(ThemeChoice.WARM_MINIMAL, "alerts-all-warm", null)
        assertTrue(bitmap.distinctColors() > 8)
        assertTrue(bitmap.luminanceAt(4, 4) > 0.6f)
    }

    @Test
    fun `one filter in Night Glass`() {
        assertTrue(render(ThemeChoice.NIGHT_GLASS, "alerts-camera-night", AlertChannel.CAMERA).distinctColors() > 8)
    }

    @Test
    fun `an empty filter in Warm Minimal`() {
        assertTrue(render(ThemeChoice.WARM_MINIMAL, "alerts-empty-warm", AlertChannel.MISC).distinctColors() > 4)
    }
}
